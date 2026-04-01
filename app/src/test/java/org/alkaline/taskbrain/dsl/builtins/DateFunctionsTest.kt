package org.alkaline.taskbrain.dsl.builtins

import org.alkaline.taskbrain.dsl.language.Lexer
import org.alkaline.taskbrain.dsl.language.Parser
import org.alkaline.taskbrain.dsl.runtime.values.DateVal
import org.alkaline.taskbrain.dsl.runtime.values.DslValue
import org.alkaline.taskbrain.dsl.runtime.Environment
import org.alkaline.taskbrain.dsl.runtime.ExecutionException
import org.alkaline.taskbrain.dsl.runtime.Executor
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * Tests for DateFunctions (parse_date).
 *
 * Milestone 8.
 */
class DateFunctionsTest {

    private lateinit var executor: Executor

    @Before
    fun setUp() {
        executor = Executor()
    }

    private fun execute(source: String): DslValue {
        val tokens = Lexer(source).tokenize()
        val directive = Parser(tokens, source).parseDirective()
        return executor.execute(directive, Environment())
    }

    // region parse_date tests

    @Test
    fun `parse_date parses standard ISO date`() {
        val result = execute("[parse_date \"2026-01-15\"]")

        assertTrue(result is DateVal)
        assertEquals(LocalDate.of(2026, 1, 15), (result as DateVal).value)
    }

    @Test
    fun `parse_date parses edge case dates`() {
        // January 1st
        assertEquals(
            LocalDate.of(2026, 1, 1),
            (execute("[parse_date \"2026-01-01\"]") as DateVal).value
        )
        // December 31st
        assertEquals(
            LocalDate.of(2026, 12, 31),
            (execute("[parse_date \"2026-12-31\"]") as DateVal).value
        )
        // Leap year date
        assertEquals(
            LocalDate.of(2024, 2, 29),
            (execute("[parse_date \"2024-02-29\"]") as DateVal).value
        )
    }

    @Test
    fun `parse_date throws on invalid format`() {
        val exception = assertThrows(ExecutionException::class.java) {
            execute("[parse_date \"01-15-2026\"]")  // US format, not ISO
        }
        assertTrue(exception.message!!.contains("failed to parse date"))
    }

    @Test
    fun `parse_date throws on invalid date`() {
        val exception = assertThrows(ExecutionException::class.java) {
            execute("[parse_date \"2026-02-30\"]")  // Feb 30 doesn't exist
        }
        assertTrue(exception.message!!.contains("failed to parse date"))
    }

    @Test
    fun `parse_date throws on non-string argument`() {
        val exception = assertThrows(ExecutionException::class.java) {
            execute("[parse_date 20260115]")
        }
        assertTrue(exception.message!!.contains("must be a string"))
    }

    @Test
    fun `parse_date is static function`() {
        assertFalse(org.alkaline.taskbrain.dsl.runtime.BuiltinRegistry.isDynamic("parse_date"))
    }

    // endregion
}
