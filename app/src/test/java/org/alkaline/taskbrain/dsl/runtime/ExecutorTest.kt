package org.alkaline.taskbrain.dsl.runtime

import org.alkaline.taskbrain.dsl.language.CallExpr
import org.alkaline.taskbrain.dsl.runtime.values.DateTimeVal
import org.alkaline.taskbrain.dsl.runtime.values.DateVal
import org.alkaline.taskbrain.dsl.runtime.values.DslValue
import org.alkaline.taskbrain.dsl.runtime.values.NumberVal
import org.alkaline.taskbrain.dsl.runtime.values.StringVal
import org.alkaline.taskbrain.dsl.runtime.values.TimeVal
import org.alkaline.taskbrain.dsl.language.Directive
import org.alkaline.taskbrain.dsl.language.DynamicCallAnalyzer
import org.alkaline.taskbrain.dsl.language.Lexer
import org.alkaline.taskbrain.dsl.language.NumberLiteral
import org.alkaline.taskbrain.dsl.language.ParseException
import org.alkaline.taskbrain.dsl.language.Parser
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class ExecutorTest {

    private lateinit var executor: Executor

    @Before
    fun setUp() {
        executor = Executor()
    }

    private fun execute(source: String): DslValue {
        val tokens = Lexer(source).tokenize()
        val directive = Parser(tokens, source).parseDirective()
        return executor.execute(directive)
    }

    // region Number evaluation

    @Test
    fun `evaluates integer to NumberVal`() {
        val result = execute("[42]")

        assertTrue(result is NumberVal)
        assertEquals(42.0, (result as NumberVal).value, 0.0)
    }

    @Test
    fun `evaluates decimal to NumberVal`() {
        val result = execute("[3.14]")

        assertTrue(result is NumberVal)
        assertEquals(3.14, (result as NumberVal).value, 0.0)
    }

    @Test
    fun `evaluates zero`() {
        val result = execute("[0]")

        assertTrue(result is NumberVal)
        assertEquals(0.0, (result as NumberVal).value, 0.0)
    }

    // endregion

    // region String evaluation

    @Test
    fun `evaluates string to StringVal`() {
        val result = execute("[\"hello\"]")

        assertTrue(result is StringVal)
        assertEquals("hello", (result as StringVal).value)
    }

    @Test
    fun `evaluates empty string`() {
        val result = execute("[\"\"]")

        assertTrue(result is StringVal)
        assertEquals("", (result as StringVal).value)
    }

    @Test
    fun `evaluates string with special characters`() {
        val result = execute("[\"hello-world_123\"]")

        assertTrue(result is StringVal)
        assertEquals("hello-world_123", (result as StringVal).value)
    }

    // endregion

    // region Display string

    @Test
    fun `integer displays without decimal`() {
        val result = execute("[42]")
        assertEquals("42", result.toDisplayString())
    }

    @Test
    fun `decimal displays with decimal point`() {
        val result = execute("[3.14]")
        assertEquals("3.14", result.toDisplayString())
    }

    @Test
    fun `string displays its value`() {
        val result = execute("[\"hello\"]")
        assertEquals("hello", result.toDisplayString())
    }

    // endregion

    // region Serialization round-trip

    @Test
    fun `serializes and deserializes NumberVal`() {
        val original = NumberVal(42.0)
        val serialized = original.serialize()
        val deserialized = DslValue.deserialize(serialized)

        assertEquals(original, deserialized)
    }

    @Test
    fun `serializes and deserializes decimal NumberVal`() {
        val original = NumberVal(3.14159)
        val serialized = original.serialize()
        val deserialized = DslValue.deserialize(serialized)

        assertEquals(original, deserialized)
    }

    @Test
    fun `serializes and deserializes StringVal`() {
        val original = StringVal("hello world")
        val serialized = original.serialize()
        val deserialized = DslValue.deserialize(serialized)

        assertEquals(original, deserialized)
    }

    @Test
    fun `serializes and deserializes empty StringVal`() {
        val original = StringVal("")
        val serialized = original.serialize()
        val deserialized = DslValue.deserialize(serialized)

        assertEquals(original, deserialized)
    }

    @Test
    fun `serialization includes type name`() {
        val number = NumberVal(42.0)
        val string = StringVal("hello")

        assertEquals("number", number.serialize()["type"])
        assertEquals("string", string.serialize()["type"])
    }

    @Test
    fun `throws on unknown type during deserialization`() {
        val badMap = mapOf("type" to "unknown", "value" to "test")

        assertThrows(IllegalArgumentException::class.java) {
            DslValue.deserialize(badMap)
        }
    }

    @Test
    fun `throws on missing type during deserialization`() {
        val badMap = mapOf("value" to "test")

        assertThrows(IllegalArgumentException::class.java) {
            DslValue.deserialize(badMap)
        }
    }

    // endregion

    // region End-to-end

    @Test
    fun `end-to-end number parse execute serialize deserialize`() {
        // Parse and execute
        val result = execute("[42]")

        // Serialize
        val serialized = result.serialize()

        // Deserialize
        val restored = DslValue.deserialize(serialized)

        // Verify
        assertTrue(restored is NumberVal)
        assertEquals(42.0, (restored as NumberVal).value, 0.0)
        assertEquals("42", restored.toDisplayString())
    }

    @Test
    fun `end-to-end string parse execute serialize deserialize`() {
        // Parse and execute
        val result = execute("[\"hello world\"]")

        // Serialize
        val serialized = result.serialize()

        // Deserialize
        val restored = DslValue.deserialize(serialized)

        // Verify
        assertTrue(restored is StringVal)
        assertEquals("hello world", (restored as StringVal).value)
        assertEquals("hello world", restored.toDisplayString())
    }

    // endregion

    // region Date functions (Milestone 2)

    @Test
    fun `date returns today's date`() {
        // Phase 0c: bare temporal values require once[...] wrapper
        val result = execute("[once[date]]")

        assertTrue(result is DateVal)
        assertEquals(LocalDate.now(), (result as DateVal).value)
    }

    @Test
    fun `datetime returns current datetime`() {
        val before = LocalDateTime.now()
        // Phase 0c: bare temporal values require once[...] wrapper
        val result = execute("[once[datetime]]")
        val after = LocalDateTime.now()

        assertTrue(result is DateTimeVal)
        val dt = (result as DateTimeVal).value
        assertTrue(dt >= before && dt <= after)
    }

    @Test
    fun `time returns current time`() {
        val before = LocalTime.now()
        // Phase 0c: bare temporal values require once[...] wrapper
        val result = execute("[once[time]]")
        val after = LocalTime.now()

        assertTrue(result is TimeVal)
        val t = (result as TimeVal).value
        // Allow for second rollover
        assertTrue(t >= before.minusSeconds(1) && t <= after.plusSeconds(1))
    }

    // endregion

    // region Character constants (Milestone 2)

    @Test
    fun `qt returns quote character`() {
        val result = execute("[qt]")

        assertTrue(result is StringVal)
        assertEquals("\"", (result as StringVal).value)
    }

    @Test
    fun `nl returns newline character`() {
        val result = execute("[nl]")

        assertTrue(result is StringVal)
        assertEquals("\n", (result as StringVal).value)
    }

    @Test
    fun `tab returns tab character`() {
        val result = execute("[tab]")

        assertTrue(result is StringVal)
        assertEquals("\t", (result as StringVal).value)
    }

    @Test
    fun `ret returns carriage return character`() {
        val result = execute("[ret]")

        assertTrue(result is StringVal)
        assertEquals("\r", (result as StringVal).value)
    }

    // endregion

    // region Error handling (Milestone 2)

    @Test
    fun `throws on unknown function`() {
        val exception = assertThrows(ExecutionException::class.java) {
            execute("[unknown_func]")
        }
        assertTrue(exception.message!!.contains("Unknown function"))
        assertTrue(exception.message!!.contains("unknown_func"))
    }

    @Test
    fun `throws when date called with arguments`() {
        val exception = assertThrows(ExecutionException::class.java) {
            execute("[date 42]")
        }
        assertTrue(exception.message!!.contains("takes no arguments"))
    }

    // endregion

    // region Date value serialization (Milestone 2)

    @Test
    fun `serializes and deserializes DateVal`() {
        val original = DateVal(LocalDate.of(2026, 1, 25))
        val serialized = original.serialize()
        val deserialized = DslValue.deserialize(serialized)

        assertEquals(original, deserialized)
    }

    @Test
    fun `serializes and deserializes TimeVal`() {
        val original = TimeVal(LocalTime.of(14, 30, 0))
        val serialized = original.serialize()
        val deserialized = DslValue.deserialize(serialized)

        assertEquals(original, deserialized)
    }

    @Test
    fun `serializes and deserializes DateTimeVal`() {
        val original = DateTimeVal(LocalDateTime.of(2026, 1, 25, 14, 30, 0))
        val serialized = original.serialize()
        val deserialized = DslValue.deserialize(serialized)

        assertEquals(original, deserialized)
    }

    @Test
    fun `DateVal displays as ISO date`() {
        val date = DateVal(LocalDate.of(2026, 1, 25))
        assertEquals("2026-01-25", date.toDisplayString())
    }

    @Test
    fun `TimeVal displays as ISO time`() {
        val time = TimeVal(LocalTime.of(14, 30, 0))
        assertEquals("14:30:00", time.toDisplayString())
    }

    @Test
    fun `DateTimeVal displays with comma separator`() {
        val datetime = DateTimeVal(LocalDateTime.of(2026, 1, 25, 14, 30, 0))
        assertEquals("2026-01-25, 14:30:00", datetime.toDisplayString())
    }

    // endregion

    // region End-to-end function calls (Milestone 2)

    @Test
    fun `end-to-end datetime`() {
        // Parse and execute
        val before = LocalDateTime.now()
        // Phase 0c: bare temporal values require once[...] wrapper
        val result = execute("[once[datetime]]")
        val after = LocalDateTime.now()

        // Serialize
        val serialized = result.serialize()

        // Deserialize
        val restored = DslValue.deserialize(serialized)

        // Verify
        assertTrue(restored is DateTimeVal)
        val dt = (restored as DateTimeVal).value
        assertTrue(dt >= before && dt <= after)
    }

    // endregion

    // region Dynamic function classification (Milestone 2)

    @Test
    fun `date is classified as dynamic`() {
        assertTrue(BuiltinRegistry.isDynamic("date"))
    }

    @Test
    fun `datetime is classified as dynamic`() {
        assertTrue(BuiltinRegistry.isDynamic("datetime"))
    }

    @Test
    fun `time is classified as dynamic`() {
        assertTrue(BuiltinRegistry.isDynamic("time"))
    }

    @Test
    fun `qt is classified as static`() {
        assertFalse(BuiltinRegistry.isDynamic("qt"))
    }

    @Test
    fun `unknown function is classified as static`() {
        assertFalse(BuiltinRegistry.isDynamic("unknown_func"))
    }

    @Test
    fun `expression with date contains dynamic calls`() {
        val directive = parse("[date]")
        assertTrue(DynamicCallAnalyzer.containsDynamicCalls(directive.expression))
    }

    @Test
    fun `expression with datetime contains dynamic calls`() {
        val directive = parse("[datetime]")
        assertTrue(DynamicCallAnalyzer.containsDynamicCalls(directive.expression))
    }

    @Test
    fun `expression with only static functions has no dynamic calls`() {
        val directive = parse("[qt]")
        assertFalse(DynamicCallAnalyzer.containsDynamicCalls(directive.expression))
    }

    @Test
    fun `literal expression has no dynamic calls`() {
        val directive = parse("[42]")
        assertFalse(DynamicCallAnalyzer.containsDynamicCalls(directive.expression))
    }

    @Test
    fun `string literal has no dynamic calls`() {
        val directive = parse("[\"hello\"]")
        assertFalse(DynamicCallAnalyzer.containsDynamicCalls(directive.expression))
    }

    private fun parse(source: String): Directive {
        val tokens = Lexer(source).tokenize()
        return Parser(tokens, source).parseDirective()
    }

    // endregion

    // region Arithmetic functions (Milestone 3)

    @Test
    fun `add returns sum of two numbers`() {
        val result = execute("[add(1, 2)]")

        assertTrue(result is NumberVal)
        assertEquals(3.0, (result as NumberVal).value, 0.0)
    }

    @Test
    fun `add works with decimals`() {
        val result = execute("[add(1.5, 2.5)]")

        assertTrue(result is NumberVal)
        assertEquals(4.0, (result as NumberVal).value, 0.0)
    }

    @Test
    fun `sub returns difference`() {
        val result = execute("[sub(5, 3)]")

        assertTrue(result is NumberVal)
        assertEquals(2.0, (result as NumberVal).value, 0.0)
    }

    @Test
    fun `sub can produce negative result`() {
        val result = execute("[sub(3, 5)]")

        assertTrue(result is NumberVal)
        assertEquals(-2.0, (result as NumberVal).value, 0.0)
    }

    @Test
    fun `mul returns product`() {
        val result = execute("[mul(3, 4)]")

        assertTrue(result is NumberVal)
        assertEquals(12.0, (result as NumberVal).value, 0.0)
    }

    @Test
    fun `div returns quotient`() {
        val result = execute("[div(10, 4)]")

        assertTrue(result is NumberVal)
        assertEquals(2.5, (result as NumberVal).value, 0.0)
    }

    @Test
    fun `div throws on division by zero`() {
        val exception = assertThrows(ExecutionException::class.java) {
            execute("[div(10, 0)]")
        }
        assertTrue(exception.message!!.contains("Division by zero"))
    }

    @Test
    fun `mod returns remainder`() {
        val result = execute("[mod(10, 3)]")

        assertTrue(result is NumberVal)
        assertEquals(1.0, (result as NumberVal).value, 0.0)
    }

    @Test
    fun `mod throws on modulo by zero`() {
        val exception = assertThrows(ExecutionException::class.java) {
            execute("[mod(10, 0)]")
        }
        assertTrue(exception.message!!.contains("Modulo by zero"))
    }

    // endregion

    // region Nested calls (Milestone 3)

    @Test
    fun `nested arithmetic calls`() {
        val result = execute("[add(mul(2, 3), 4)]")

        assertTrue(result is NumberVal)
        assertEquals(10.0, (result as NumberVal).value, 0.0)
    }

    @Test
    fun `deeply nested calls`() {
        val result = execute("[add(mul(2, 3), sub(10, 5))]")

        assertTrue(result is NumberVal)
        assertEquals(11.0, (result as NumberVal).value, 0.0)
    }

    @Test
    fun `triple nested calls`() {
        // (2 * 3) + (10 - (4 / 2)) = 6 + (10 - 2) = 6 + 8 = 14
        val result = execute("[add(mul(2, 3), sub(10, div(4, 2)))]")

        assertTrue(result is NumberVal)
        assertEquals(14.0, (result as NumberVal).value, 0.0)
    }

    // endregion

    // region Named arguments parsing (Milestone 3)

    @Test
    fun `parses named arguments`() {
        // Create a simple test function that accepts named arguments
        val directive = parse("[test(foo: 42)]")
        val expr = directive.expression as CallExpr

        assertEquals("test", expr.name)
        assertEquals(0, expr.args.size)
        assertEquals(1, expr.namedArgs.size)
        assertEquals("foo", expr.namedArgs[0].name)
        assertTrue(expr.namedArgs[0].value is NumberLiteral)
        assertEquals(42.0, (expr.namedArgs[0].value as NumberLiteral).value, 0.0)
    }

    @Test
    fun `parses mixed positional and named arguments`() {
        val directive = parse("[test(1, 2, foo: 3)]")
        val expr = directive.expression as CallExpr

        assertEquals("test", expr.name)
        assertEquals(2, expr.args.size)
        assertEquals(1, expr.namedArgs.size)

        assertEquals(1.0, (expr.args[0] as NumberLiteral).value, 0.0)
        assertEquals(2.0, (expr.args[1] as NumberLiteral).value, 0.0)
        assertEquals("foo", expr.namedArgs[0].name)
        assertEquals(3.0, (expr.namedArgs[0].value as NumberLiteral).value, 0.0)
    }

    @Test
    fun `parses multiple named arguments`() {
        val directive = parse("[test(a: 1, b: 2, c: 3)]")
        val expr = directive.expression as CallExpr

        assertEquals("test", expr.name)
        assertEquals(0, expr.args.size)
        assertEquals(3, expr.namedArgs.size)

        assertEquals("a", expr.namedArgs[0].name)
        assertEquals("b", expr.namedArgs[1].name)
        assertEquals("c", expr.namedArgs[2].name)
    }

    @Test
    fun `positional argument after named throws parse error`() {
        val exception = assertThrows(ParseException::class.java) {
            parse("[test(foo: 1, 2)]")
        }
        assertTrue(exception.message!!.contains("Positional argument cannot follow named argument"))
    }

    // endregion

    // region Arithmetic error handling (Milestone 3)

    @Test
    fun `add with wrong arity throws`() {
        val exception = assertThrows(ExecutionException::class.java) {
            execute("[add(1)]")
        }
        assertTrue(exception.message!!.contains("requires 2 arguments"))
    }

    @Test
    fun `add with non-number throws`() {
        val exception = assertThrows(ExecutionException::class.java) {
            execute("[add(1, \"hello\")]")
        }
        assertTrue(exception.message!!.contains("must be a number"))
    }

    @Test
    fun `arithmetic functions are classified as static`() {
        assertFalse(BuiltinRegistry.isDynamic("add"))
        assertFalse(BuiltinRegistry.isDynamic("sub"))
        assertFalse(BuiltinRegistry.isDynamic("mul"))
        assertFalse(BuiltinRegistry.isDynamic("div"))
        assertFalse(BuiltinRegistry.isDynamic("mod"))
    }

    // endregion

    // region Empty parentheses (Milestone 3)

    @Test
    fun `empty parentheses work for zero-arg functions`() {
        // Phase 0c: bare temporal values require once[...] wrapper
        val result = execute("[once[date()]]")

        assertTrue(result is DateVal)
        assertEquals(LocalDate.now(), (result as DateVal).value)
    }

    // endregion
}
