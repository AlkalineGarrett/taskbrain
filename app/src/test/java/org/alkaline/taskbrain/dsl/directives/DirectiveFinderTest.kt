package org.alkaline.taskbrain.dsl.directives

import org.alkaline.taskbrain.dsl.runtime.values.NumberVal
import org.alkaline.taskbrain.dsl.runtime.values.StringVal
import org.junit.Assert.*
import org.junit.Test

class DirectiveFinderTest {

    private companion object {
        const val TEST_LINE_ID = "test-line"
    }

    // region Finding directives

    @Test
    fun `finds single directive in content`() {
        val content = "Some text [42] more text"
        val directives = DirectiveFinder.findDirectives(content)

        assertEquals(1, directives.size)
        assertEquals("[42]", directives[0].sourceText)
        assertEquals(10, directives[0].startOffset)
        assertEquals(14, directives[0].endOffset)
    }

    @Test
    fun `finds multiple directives in content`() {
        val content = "[1] and [2] and [3]"
        val directives = DirectiveFinder.findDirectives(content)

        assertEquals(3, directives.size)
        assertEquals("[1]", directives[0].sourceText)
        assertEquals("[2]", directives[1].sourceText)
        assertEquals("[3]", directives[2].sourceText)
    }

    @Test
    fun `finds string directive`() {
        val content = "Result: [\"hello\"]"
        val directives = DirectiveFinder.findDirectives(content)

        assertEquals(1, directives.size)
        assertEquals("[\"hello\"]", directives[0].sourceText)
    }

    @Test
    fun `returns empty list for no directives`() {
        val content = "No directives here"
        val directives = DirectiveFinder.findDirectives(content)

        assertTrue(directives.isEmpty())
    }

    @Test
    fun `containsDirectives returns true when directives exist`() {
        assertTrue(DirectiveFinder.containsDirectives("Has [42] directive"))
    }

    @Test
    fun `containsDirectives returns false when no directives`() {
        assertFalse(DirectiveFinder.containsDirectives("No directives"))
    }

    @Test
    fun `finds directive at start of content`() {
        val content = "[42] at start"
        val directives = DirectiveFinder.findDirectives(content)

        assertEquals(1, directives.size)
        assertEquals(0, directives[0].startOffset)
    }

    @Test
    fun `finds directive at end of content`() {
        val content = "at end [42]"
        val directives = DirectiveFinder.findDirectives(content)

        assertEquals(1, directives.size)
        assertEquals(7, directives[0].startOffset)
        assertEquals(11, directives[0].endOffset)
    }

    @Test
    fun `handles empty content`() {
        val directives = DirectiveFinder.findDirectives("")
        assertTrue(directives.isEmpty())
    }

    // endregion

    // region Nested brackets (Milestone 8 - lambdas)

    @Test
    fun `finds directive with nested brackets`() {
        val content = "Test [[i]] here"
        val directives = DirectiveFinder.findDirectives(content)

        assertEquals(1, directives.size)
        assertEquals("[[i]]", directives[0].sourceText)
        assertEquals(5, directives[0].startOffset)
        assertEquals(10, directives[0].endOffset)
    }

    @Test
    fun `finds directive with deeply nested brackets`() {
        val content = "[[matches(i.path, pattern(digit*4))]]"
        val directives = DirectiveFinder.findDirectives(content)

        assertEquals(1, directives.size)
        assertEquals(content, directives[0].sourceText)
    }

    @Test
    fun `finds multiple directives with nested brackets`() {
        val content = "[[i.path]] and [[i.name]]"
        val directives = DirectiveFinder.findDirectives(content)

        assertEquals(2, directives.size)
        assertEquals("[[i.path]]", directives[0].sourceText)
        assertEquals("[[i.name]]", directives[1].sourceText)
    }

    @Test
    fun `finds mixed nested and simple directives`() {
        val content = "[42] then [[i]] then [\"hello\"]"
        val directives = DirectiveFinder.findDirectives(content)

        assertEquals(3, directives.size)
        assertEquals("[42]", directives[0].sourceText)
        assertEquals("[[i]]", directives[1].sourceText)
        assertEquals("[\"hello\"]", directives[2].sourceText)
    }

    @Test
    fun `handles unmatched opening bracket`() {
        val content = "Test [unclosed"
        val directives = DirectiveFinder.findDirectives(content)

        assertTrue(directives.isEmpty())
    }

    @Test
    fun `handles unmatched nested bracket`() {
        val content = "Test [[unclosed]"
        val directives = DirectiveFinder.findDirectives(content)

        // The outer bracket is unmatched, so no directive found
        assertTrue(directives.isEmpty())
    }

    // endregion

    // region Executing directives

    @Test
    fun `executes number directive successfully`() {
        val execResult = DirectiveFinder.executeDirective("[42]")

        assertNull(execResult.result.error)
        assertNotNull(execResult.result.result)
        assertEquals(42.0, (execResult.result.toValue() as NumberVal).value, 0.0)
    }

    @Test
    fun `executes string directive successfully`() {
        val execResult = DirectiveFinder.executeDirective("[\"hello\"]")

        assertNull(execResult.result.error)
        assertEquals("hello", (execResult.result.toValue() as StringVal).value)
    }

    @Test
    fun `returns error for invalid directive`() {
        val execResult = DirectiveFinder.executeDirective("[invalid@syntax]")

        assertNotNull(execResult.result.error)
        assertTrue(execResult.result.error!!.contains("error"))
    }

    @Test
    fun `returns error for empty directive`() {
        val execResult = DirectiveFinder.executeDirective("[]")

        assertNotNull(execResult.result.error)
    }

    @Test
    fun `returns error for unclosed string`() {
        val execResult = DirectiveFinder.executeDirective("[\"unclosed]")

        assertNotNull(execResult.result.error)
        assertTrue(execResult.result.error!!.contains("Lexer error"))
    }

    // endregion

    // region Execute all directives

    @Test
    fun `executeAllDirectives returns results for all directives`() {
        val content = "First [42] second [\"hello\"]"
        val results = DirectiveFinder.executeAllDirectives(content, TEST_LINE_ID)

        assertEquals(2, results.size)
    }

    @Test
    fun `executeAllDirectives handles mixed success and error`() {
        val content = "[42] and [invalid@]"
        val results = DirectiveFinder.executeAllDirectives(content, TEST_LINE_ID)

        assertEquals(2, results.size)

        // Find the successful one
        val successResult = results.values.find { it.error == null }
        assertNotNull(successResult)
        assertEquals(42.0, (successResult!!.toValue() as NumberVal).value, 0.0)

        // Find the failed one
        val errorResult = results.values.find { it.error != null }
        assertNotNull(errorResult)
    }

    @Test
    fun `executeAllDirectives returns empty map for no directives`() {
        val results = DirectiveFinder.executeAllDirectives("No directives here", TEST_LINE_ID)
        assertTrue(results.isEmpty())
    }

    // endregion

    // region No-effect warnings (Milestone 8)

    @Test
    fun `lambda at top level returns warning`() {
        val execResult = DirectiveFinder.executeDirective("[[i]]")

        assertNull(execResult.result.error)
        assertNotNull(execResult.result.warning)
        assertEquals(DirectiveWarningType.NO_EFFECT_LAMBDA, execResult.result.warning)
        assertTrue(execResult.result.hasWarning)
    }

    @Test
    fun `lambda with body at top level returns warning`() {
        val execResult = DirectiveFinder.executeDirective("[[i.path]]")

        assertNull(execResult.result.error)
        assertEquals(DirectiveWarningType.NO_EFFECT_LAMBDA, execResult.result.warning)
    }

    @Test
    fun `pattern at top level returns warning`() {
        val execResult = DirectiveFinder.executeDirective("[pattern(digit*4)]")

        assertNull(execResult.result.error)
        assertNotNull(execResult.result.warning)
        assertEquals(DirectiveWarningType.NO_EFFECT_PATTERN, execResult.result.warning)
    }

    @Test
    fun `warning display message is descriptive`() {
        assertEquals("Uncalled lambda has no effect", DirectiveWarningType.NO_EFFECT_LAMBDA.displayMessage)
        assertEquals("Unused pattern has no effect", DirectiveWarningType.NO_EFFECT_PATTERN.displayMessage)
    }

    @Test
    fun `warning result toDisplayString shows warning message`() {
        val result = DirectiveResult.warning(DirectiveWarningType.NO_EFFECT_LAMBDA)

        assertTrue(result.toDisplayString().contains("Warning"))
        assertTrue(result.toDisplayString().contains("Uncalled lambda"))
    }

    // endregion

    // region Directive hashing

    @Test
    fun `same directive text produces same hash`() {
        val content = "[42] and [42]"
        val directives = DirectiveFinder.findDirectives(content)

        assertEquals(2, directives.size)
        assertEquals(directives[0].hash(), directives[1].hash())
    }

    @Test
    fun `different directive text produces different hash`() {
        val content = "[42] and [43]"
        val directives = DirectiveFinder.findDirectives(content)

        assertEquals(2, directives.size)
        assertNotEquals(directives[0].hash(), directives[1].hash())
    }

    @Test
    fun `hash returns hex string`() {
        val directive = DirectiveFinder.findDirectives("[test]")[0]
        val hash = directive.hash()
        assertTrue(hash.matches(Regex("^[0-9a-f]+$")))
    }

    // endregion

    // region directiveKey

    @Test
    fun `directiveKey produces lineId-based key`() {
        assertEquals("abc123:15", DirectiveFinder.directiveKey("abc123", 15))
    }

    @Test
    fun `directiveKey with test lineId produces expected key`() {
        assertEquals("test-line:15", DirectiveFinder.directiveKey(TEST_LINE_ID, 15))
    }

    @Test
    fun `directiveKey formats correctly`() {
        assertEquals("note1:5", DirectiveFinder.directiveKey("note1", 5))
    }

    // endregion

    // region startOffsetFromKey

    @Test
    fun `startOffsetFromKey extracts offset from lineId key`() {
        assertEquals(15, DirectiveFinder.startOffsetFromKey("abc123:15"))
    }

    @Test
    fun `startOffsetFromKey extracts offset from test lineId key`() {
        assertEquals(20, DirectiveFinder.startOffsetFromKey("test-line:20"))
    }

    @Test
    fun `startOffsetFromKey returns null for non-numeric`() {
        assertNull(DirectiveFinder.startOffsetFromKey("bad:key:abc"))
    }

    @Test
    fun `startOffsetFromKey handles zero offset`() {
        assertEquals(0, DirectiveFinder.startOffsetFromKey("note1:0"))
        assertEquals(0, DirectiveFinder.startOffsetFromKey("test-line:0"))
    }

    @Test
    fun `startOffsetFromKey returns null for key with no colon`() {
        assertNull(DirectiveFinder.startOffsetFromKey("nocolon"))
    }

    // endregion
}
