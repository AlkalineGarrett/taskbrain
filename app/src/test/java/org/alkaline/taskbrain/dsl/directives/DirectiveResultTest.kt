package org.alkaline.taskbrain.dsl.directives

import org.alkaline.taskbrain.dsl.runtime.NumberVal
import org.alkaline.taskbrain.dsl.runtime.StringVal
import org.junit.Assert.*
import org.junit.Test

class DirectiveResultTest {

    // region Success factory

    @Test
    fun `success creates result with serialized value`() {
        val result = DirectiveResult.success(NumberVal(42.0))

        assertNotNull(result.result)
        assertNull(result.error)
        assertTrue(result.collapsed)
    }

    @Test
    fun `success respects collapsed parameter`() {
        val expanded = DirectiveResult.success(NumberVal(42.0), collapsed = false)
        assertFalse(expanded.collapsed)
    }

    @Test
    fun `success serializes number correctly`() {
        val result = DirectiveResult.success(NumberVal(42.0))
        val value = result.toValue()

        assertTrue(value is NumberVal)
        assertEquals(42.0, (value as NumberVal).value, 0.0)
    }

    @Test
    fun `success serializes string correctly`() {
        val result = DirectiveResult.success(StringVal("hello"))
        val value = result.toValue()

        assertTrue(value is StringVal)
        assertEquals("hello", (value as StringVal).value)
    }

    // endregion

    // region Failure factory

    @Test
    fun `failure creates result with error message`() {
        val result = DirectiveResult.failure("Something went wrong")

        assertNull(result.result)
        assertEquals("Something went wrong", result.error)
        assertTrue(result.collapsed)
    }

    @Test
    fun `failure respects collapsed parameter`() {
        val expanded = DirectiveResult.failure("error", collapsed = false)
        assertFalse(expanded.collapsed)
    }

    @Test
    fun `failure toValue returns null`() {
        val result = DirectiveResult.failure("error")
        assertNull(result.toValue())
    }

    // endregion

    // region toValue

    @Test
    fun `toValue returns null for null result`() {
        val result = DirectiveResult(result = null)
        assertNull(result.toValue())
    }

    @Test
    fun `toValue returns null for invalid serialized data`() {
        val result = DirectiveResult(result = mapOf("type" to "unknown", "value" to "x"))
        assertNull(result.toValue())
    }

    @Test
    fun `toValue deserializes number`() {
        val result = DirectiveResult(result = mapOf("type" to "number", "value" to 42.0))
        val value = result.toValue()

        assertTrue(value is NumberVal)
        assertEquals(42.0, (value as NumberVal).value, 0.0)
    }

    @Test
    fun `toValue deserializes string`() {
        val result = DirectiveResult(result = mapOf("type" to "string", "value" to "hello"))
        val value = result.toValue()

        assertTrue(value is StringVal)
        assertEquals("hello", (value as StringVal).value)
    }

    // endregion

    // region hashDirective

    @Test
    fun `hashDirective produces consistent hash`() {
        val hash1 = DirectiveResult.hashDirective("[42]")
        val hash2 = DirectiveResult.hashDirective("[42]")

        assertEquals(hash1, hash2)
    }

    @Test
    fun `hashDirective produces different hashes for different input`() {
        val hash1 = DirectiveResult.hashDirective("[42]")
        val hash2 = DirectiveResult.hashDirective("[43]")

        assertNotEquals(hash1, hash2)
    }

    @Test
    fun `hashDirective produces 16 character hex string`() {
        val hash = DirectiveResult.hashDirective("[42]")

        assertEquals(16, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `hashDirective handles empty string`() {
        val hash = DirectiveResult.hashDirective("")
        assertEquals(16, hash.length)
    }

    @Test
    fun `hashDirective handles unicode`() {
        val hash = DirectiveResult.hashDirective("[\"日本語\"]")
        assertEquals(16, hash.length)
    }

    @Test
    fun `hashDirective matches web implementation`() {
        // Known FNV-1a 64-bit values verified against TypeScript implementation
        assertEquals("a430d84680aabd0b", DirectiveResult.hashDirective("hello"))
        assertEquals("11cfc5a73ae59ce5", DirectiveResult.hashDirective("[test]"))
    }

    // endregion

    // region Data class defaults

    @Test
    fun `default constructor sets collapsed to true`() {
        val result = DirectiveResult()
        assertTrue(result.collapsed)
    }

    @Test
    fun `default constructor sets null values`() {
        val result = DirectiveResult()
        assertNull(result.result)
        assertNull(result.executedAt)
        assertNull(result.error)
    }

    // endregion
}
