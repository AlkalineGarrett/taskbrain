package org.alkaline.taskbrain.dsl.language

import org.alkaline.taskbrain.dsl.runtime.AlarmVal
import org.alkaline.taskbrain.dsl.runtime.ButtonVal
import org.alkaline.taskbrain.dsl.runtime.DslValue
import org.alkaline.taskbrain.dsl.runtime.Environment
import org.alkaline.taskbrain.dsl.runtime.ExecutionException
import org.alkaline.taskbrain.dsl.runtime.Executor
import org.alkaline.taskbrain.dsl.runtime.ScheduleFrequency
import org.alkaline.taskbrain.dsl.runtime.ScheduleVal
import org.alkaline.taskbrain.dsl.runtime.StringVal
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for Phase 0f: button() and schedule() functions.
 *
 * Tests:
 * - button(label, action) creates ButtonVal
 * - schedule(frequency, action) creates ScheduleVal
 * - Schedule frequency constants (daily, hourly, weekly)
 * - Idempotency analysis for button/schedule wrappers
 * - Error handling for invalid arguments
 */
class ActionFunctionsTest {

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

    // region button() function

    @Test
    fun `button with label and action creates ButtonVal`() {
        val result = execute("[button(\"Click me\", [5])]")

        assertTrue(result is ButtonVal)
        val button = result as ButtonVal
        assertEquals("Click me", button.label)
        assertNotNull(button.action)
    }

    @Test
    fun `button action is a lambda`() {
        val result = execute("[button(\"Test\", [add(1, 2)])]")

        assertTrue(result is ButtonVal)
        val button = result as ButtonVal
        assertEquals(listOf("i"), button.action.params)
    }

    @Test
    fun `button displays correctly`() {
        val result = execute("[button(\"Submit\", [5])]") as ButtonVal

        assertEquals("[Button: Submit]", result.toDisplayString())
    }

    @Test
    fun `button with variable label`() {
        val env = Environment()
        env.define("lbl", StringVal("Dynamic Label"))

        val result = execute("[button(lbl, [5])]", env)

        assertTrue(result is ButtonVal)
        assertEquals("Dynamic Label", (result as ButtonVal).label)
    }

    @Test
    fun `button missing label throws error`() {
        val exception = assertThrows(ExecutionException::class.java) {
            execute("[button([5])]")
        }
        assertTrue(exception.message!!.contains("label"))
    }

    @Test
    fun `button missing action throws error`() {
        val exception = assertThrows(ExecutionException::class.java) {
            execute("[button(\"Click\")]")
        }
        assertTrue(exception.message!!.contains("action"))
    }

    @Test
    fun `button with non-lambda action throws error`() {
        val exception = assertThrows(ExecutionException::class.java) {
            execute("[button(\"Click\", 5)]")
        }
        assertTrue(exception.message!!.contains("lambda"))
    }

    // endregion

    // region schedule() function

    @Test
    fun `schedule with daily frequency creates ScheduleVal`() {
        val result = execute("[schedule(daily, [5])]")

        assertTrue(result is ScheduleVal)
        val schedule = result as ScheduleVal
        assertEquals(ScheduleFrequency.DAILY, schedule.frequency)
        assertNotNull(schedule.action)
    }

    @Test
    fun `schedule with hourly frequency creates ScheduleVal`() {
        val result = execute("[schedule(hourly, [5])]")

        assertTrue(result is ScheduleVal)
        assertEquals(ScheduleFrequency.HOURLY, (result as ScheduleVal).frequency)
    }

    @Test
    fun `schedule with weekly frequency creates ScheduleVal`() {
        val result = execute("[schedule(weekly, [5])]")

        assertTrue(result is ScheduleVal)
        assertEquals(ScheduleFrequency.WEEKLY, (result as ScheduleVal).frequency)
    }

    @Test
    fun `schedule displays correctly`() {
        val result = execute("[schedule(daily, [5])]") as ScheduleVal

        assertEquals("[Schedule: daily]", result.toDisplayString())
    }

    @Test
    fun `schedule action is a lambda`() {
        val result = execute("[schedule(daily, [add(i, 1)])]")

        assertTrue(result is ScheduleVal)
        val schedule = result as ScheduleVal
        assertEquals(listOf("i"), schedule.action.params)
    }

    @Test
    fun `schedule missing frequency throws error`() {
        val exception = assertThrows(ExecutionException::class.java) {
            execute("[schedule([5])]")
        }
        assertTrue(exception.message!!.contains("frequency") || exception.message!!.contains("schedule"))
    }

    @Test
    fun `schedule with invalid frequency throws error`() {
        val exception = assertThrows(ExecutionException::class.java) {
            execute("[schedule(\"invalid\", [5])]")
        }
        assertTrue(exception.message!!.contains("Unknown schedule frequency"))
    }

    @Test
    fun `schedule with non-lambda action throws error`() {
        val exception = assertThrows(ExecutionException::class.java) {
            execute("[schedule(daily, 5)]")
        }
        assertTrue(exception.message!!.contains("lambda"))
    }

    // endregion

    // region Schedule frequency constants

    @Test
    fun `daily constant returns daily identifier`() {
        val result = execute("[daily]")

        assertTrue(result is StringVal)
        assertEquals("daily", (result as StringVal).value)
    }

    @Test
    fun `hourly constant returns hourly identifier`() {
        val result = execute("[hourly]")

        assertTrue(result is StringVal)
        assertEquals("hourly", (result as StringVal).value)
    }

    @Test
    fun `weekly constant returns weekly identifier`() {
        val result = execute("[weekly]")

        assertTrue(result is StringVal)
        assertEquals("weekly", (result as StringVal).value)
    }

    // endregion

    // region Time-specific frequency functions

    @Test
    fun `daily_at returns ScheduleVal with time`() {
        val result = execute("[daily_at(\"09:00\")]")

        assertTrue(result is ScheduleVal)
        val schedule = result as ScheduleVal
        assertEquals(ScheduleFrequency.DAILY, schedule.frequency)
        assertEquals("09:00", schedule.atTime)
    }

    @Test
    fun `weekly_at returns ScheduleVal with time`() {
        val result = execute("[weekly_at(\"14:30\")]")

        assertTrue(result is ScheduleVal)
        val schedule = result as ScheduleVal
        assertEquals(ScheduleFrequency.WEEKLY, schedule.frequency)
        assertEquals("14:30", schedule.atTime)
    }

    @Test
    fun `schedule with daily_at creates ScheduleVal with time`() {
        val result = execute("[schedule(daily_at(\"09:00\"), [5])]")

        assertTrue(result is ScheduleVal)
        val schedule = result as ScheduleVal
        assertEquals(ScheduleFrequency.DAILY, schedule.frequency)
        assertEquals("09:00", schedule.atTime)
        assertNotNull(schedule.action)
    }

    @Test
    fun `schedule with weekly_at creates ScheduleVal with time`() {
        val result = execute("[schedule(weekly_at(\"08:30\"), [5])]")

        assertTrue(result is ScheduleVal)
        val schedule = result as ScheduleVal
        assertEquals(ScheduleFrequency.WEEKLY, schedule.frequency)
        assertEquals("08:30", schedule.atTime)
        assertNotNull(schedule.action)
    }

    @Test
    fun `daily_at with single digit hour is valid`() {
        // Single-digit hours like "9:00" are valid (matches [01]?\d in regex)
        val result = execute("[daily_at(\"9:00\")]")
        assertTrue(result is ScheduleVal)
        assertEquals("9:00", (result as ScheduleVal).atTime)
    }

    @Test
    fun `daily_at with completely invalid time throws error`() {
        val exception = assertThrows(ExecutionException::class.java) {
            execute("[daily_at(\"25:00\")]")  // Invalid hour
        }
        assertTrue(exception.message!!.contains("HH:mm format"))
    }

    @Test
    fun `weekly_at with invalid time throws error`() {
        val exception = assertThrows(ExecutionException::class.java) {
            execute("[weekly_at(\"12:60\")]")  // Invalid minutes
        }
        assertTrue(exception.message!!.contains("HH:mm format"))
    }

    @Test
    fun `daily_at with no arguments throws error`() {
        val exception = assertThrows(ExecutionException::class.java) {
            execute("[daily_at()]")
        }
        assertTrue(exception.message!!.contains("time"))
    }

    @Test
    fun `schedule with daily_at displays time in description`() {
        val result = execute("[schedule(daily_at(\"09:00\"), [5])]") as ScheduleVal

        assertEquals("[Schedule: daily at 09:00]", result.toDisplayString())
    }

    @Test
    fun `schedule without time has no atTime`() {
        val result = execute("[schedule(daily, [5])]")

        assertTrue(result is ScheduleVal)
        assertNull((result as ScheduleVal).atTime)
    }

    // endregion

    // region Idempotency analysis

    @Test
    fun `button is idempotent even with non-idempotent action`() {
        // button wraps non-idempotent actions, making the whole expression idempotent
        val directive = parse("[button(\"Create\", [new(path: \"test\")])]")
        val result = IdempotencyAnalyzer.analyze(directive.expression)
        assertTrue(result.isIdempotent)
    }

    @Test
    fun `schedule is idempotent even with non-idempotent action`() {
        val directive = parse("[schedule(daily, [new(path: \"test\")])]")
        val result = IdempotencyAnalyzer.analyze(directive.expression)
        assertTrue(result.isIdempotent)
    }

    @Test
    fun `button with append action is idempotent`() {
        val directive = parse("[button(\"Done\", [.append(\" done\")])]")
        val result = IdempotencyAnalyzer.analyze(directive.expression)
        assertTrue(result.isIdempotent)
    }

    @Test
    fun `bare new is not idempotent`() {
        val directive = parse("[new(path: \"test\")]")
        val result = IdempotencyAnalyzer.analyze(directive.expression)
        assertFalse(result.isIdempotent)
        assertTrue(result.nonIdempotentReason!!.contains("button") || result.nonIdempotentReason!!.contains("schedule"))
    }

    @Test
    fun `bare append is not idempotent`() {
        val directive = parse("[.append(\"text\")]")
        val result = IdempotencyAnalyzer.analyze(directive.expression)
        assertFalse(result.isIdempotent)
        assertTrue(result.nonIdempotentReason!!.contains("button") || result.nonIdempotentReason!!.contains("schedule"))
    }

    // endregion

    // region Dynamic analysis

    @Test
    fun `button is not dynamic`() {
        val directive = parse("[button(\"Test\", [5])]")
        assertFalse(DynamicCallAnalyzer.containsDynamicCalls(directive.expression))
    }

    @Test
    fun `schedule is not dynamic`() {
        val directive = parse("[schedule(daily, [5])]")
        assertFalse(DynamicCallAnalyzer.containsDynamicCalls(directive.expression))
    }

    @Test
    fun `button with dynamic action is dynamic`() {
        val directive = parse("[button(\"Test\", [time])]")
        // The lambda body contains dynamic call, so the lambda is dynamic
        assertTrue(DynamicCallAnalyzer.containsDynamicCalls(directive.expression))
    }

    // endregion

    // region alarm() function

    @Test
    fun `alarm with id creates AlarmVal`() {
        val result = execute("[alarm(\"f3GxY2abc\")]")

        assertTrue(result is AlarmVal)
        val alarm = result as AlarmVal
        assertEquals("f3GxY2abc", alarm.alarmId)
    }

    @Test
    fun `alarm displays as clock emoji`() {
        val result = execute("[alarm(\"test123\")]") as AlarmVal

        assertEquals("⏰", result.toDisplayString())
    }

    @Test
    fun `alarm with variable id`() {
        val env = Environment()
        env.define("id", StringVal("dynamicId"))

        val result = execute("[alarm(id)]", env)

        assertTrue(result is AlarmVal)
        assertEquals("dynamicId", (result as AlarmVal).alarmId)
    }

    @Test
    fun `alarm missing id throws error`() {
        val exception = assertThrows(ExecutionException::class.java) {
            execute("[alarm()]")
        }
        assertTrue(exception.message!!.contains("id"))
    }

    @Test
    fun `alarm with non-string id throws error`() {
        val exception = assertThrows(ExecutionException::class.java) {
            execute("[alarm(42)]")
        }
        assertTrue(exception.message!!.contains("string"))
    }

    @Test
    fun `alarm serialization roundtrip`() {
        val original = AlarmVal("abc123")
        val serialized = original.serialize()
        val deserialized = DslValue.deserialize(serialized)

        assertTrue(deserialized is AlarmVal)
        assertEquals("abc123", (deserialized as AlarmVal).alarmId)
    }

    @Test
    fun `alarm is idempotent`() {
        val directive = parse("[alarm(\"test\")]")
        val result = IdempotencyAnalyzer.analyze(directive.expression)
        assertTrue(result.isIdempotent)
    }

    @Test
    fun `alarm is not dynamic`() {
        val directive = parse("[alarm(\"test\")]")
        assertFalse(DynamicCallAnalyzer.containsDynamicCalls(directive.expression))
    }

    // endregion

    // region Complex scenarios

    @Test
    fun `button can be assigned to variable`() {
        val result = execute("[b: button(\"Test\", [5]); b]")

        assertTrue(result is ButtonVal)
    }

    @Test
    fun `schedule can be assigned to variable`() {
        val result = execute("[s: schedule(daily, [5]); s]")

        assertTrue(result is ScheduleVal)
    }

    @Test
    fun `multiple buttons in statement list`() {
        val result = execute("[button(\"A\", [1]); button(\"B\", [2])]")

        // Last statement is returned
        assertTrue(result is ButtonVal)
        assertEquals("B", (result as ButtonVal).label)
    }

    // endregion
}
