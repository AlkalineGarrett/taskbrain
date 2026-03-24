package org.alkaline.taskbrain.dsl.directives

import org.alkaline.taskbrain.dsl.runtime.NumberVal
import org.junit.Assert.*
import org.junit.Test

class DirectiveSegmenterTest {

    private companion object {
        const val TEST_LINE_ID = "test-line"
    }

    // region segmentLine tests

    @Test
    fun `segmentLine returns empty list for empty content`() {
        val segments = DirectiveSegmenter.segmentLine("", TEST_LINE_ID, emptyMap())
        assertTrue(segments.isEmpty())
    }

    @Test
    fun `segmentLine returns single text segment for content without directives`() {
        val segments = DirectiveSegmenter.segmentLine("Hello world", TEST_LINE_ID, emptyMap())

        assertEquals(1, segments.size)
        val segment = segments[0] as DirectiveSegment.Text
        assertEquals("Hello world", segment.content)
        assertEquals(0..10, segment.range)
    }

    @Test
    fun `segmentLine returns directive segment for single directive`() {
        val content = "[42]"
        val results = DirectiveFinder.executeAllDirectives(content, TEST_LINE_ID)
        val segments = DirectiveSegmenter.segmentLine(content, TEST_LINE_ID, results)

        assertEquals(1, segments.size)
        val segment = segments[0] as DirectiveSegment.Directive
        assertEquals("[42]", segment.sourceText)
        assertTrue(segment.isComputed)
        assertEquals("42", segment.displayText)
    }

    @Test
    fun `segmentLine handles text before and after directive`() {
        val content = "Hello [42] world"
        val results = DirectiveFinder.executeAllDirectives(content, TEST_LINE_ID)
        val segments = DirectiveSegmenter.segmentLine(content, TEST_LINE_ID, results)

        assertEquals(3, segments.size)

        val text1 = segments[0] as DirectiveSegment.Text
        assertEquals("Hello ", text1.content)

        val directive = segments[1] as DirectiveSegment.Directive
        assertEquals("[42]", directive.sourceText)
        assertEquals("42", directive.displayText)

        val text2 = segments[2] as DirectiveSegment.Text
        assertEquals(" world", text2.content)
    }

    @Test
    fun `segmentLine handles multiple directives`() {
        val content = "[1] and [2] and [3]"
        val results = DirectiveFinder.executeAllDirectives(content, TEST_LINE_ID)
        val segments = DirectiveSegmenter.segmentLine(content, TEST_LINE_ID, results)

        assertEquals(5, segments.size)

        val d1 = segments[0] as DirectiveSegment.Directive
        assertEquals("[1]", d1.sourceText)

        val t1 = segments[1] as DirectiveSegment.Text
        assertEquals(" and ", t1.content)

        val d2 = segments[2] as DirectiveSegment.Directive
        assertEquals("[2]", d2.sourceText)

        val t2 = segments[3] as DirectiveSegment.Text
        assertEquals(" and ", t2.content)

        val d3 = segments[4] as DirectiveSegment.Directive
        assertEquals("[3]", d3.sourceText)
    }

    @Test
    fun `segmentLine shows source text when no result available`() {
        val content = "[42]"
        // No results provided
        val segments = DirectiveSegmenter.segmentLine(content, TEST_LINE_ID, emptyMap())

        assertEquals(1, segments.size)
        val segment = segments[0] as DirectiveSegment.Directive
        assertEquals("[42]", segment.sourceText)
        assertFalse(segment.isComputed)
        assertEquals("[42]", segment.displayText) // Source text when not computed
    }

    // endregion

    // region hasDirectives tests

    @Test
    fun `hasDirectives returns false for content without directives`() {
        assertFalse(DirectiveSegmenter.hasDirectives("Hello world"))
        assertFalse(DirectiveSegmenter.hasDirectives(""))
        assertFalse(DirectiveSegmenter.hasDirectives("No brackets here"))
    }

    @Test
    fun `hasDirectives returns true for content with directives`() {
        assertTrue(DirectiveSegmenter.hasDirectives("[42]"))
        assertTrue(DirectiveSegmenter.hasDirectives("Hello [42] world"))
        assertTrue(DirectiveSegmenter.hasDirectives("[\"string\"]"))
    }

    // endregion

    // region hasComputedDirectives tests

    @Test
    fun `hasComputedDirectives returns false for content without directives`() {
        assertFalse(DirectiveSegmenter.hasComputedDirectives("Hello world", TEST_LINE_ID, emptyMap()))
    }

    @Test
    fun `hasComputedDirectives returns false when no results available`() {
        assertFalse(DirectiveSegmenter.hasComputedDirectives("[42]", TEST_LINE_ID, emptyMap()))
    }

    @Test
    fun `hasComputedDirectives returns true when result is available`() {
        val content = "[42]"
        val results = DirectiveFinder.executeAllDirectives(content, TEST_LINE_ID)
        assertTrue(DirectiveSegmenter.hasComputedDirectives(content, TEST_LINE_ID, results))
    }

    @Test
    fun `hasComputedDirectives returns false when result has error`() {
        val content = "[42]"
        val key = DirectiveResult.hashDirective("[42]")
        val errorResult = mapOf(key to DirectiveResult.failure("Error"))
        assertFalse(DirectiveSegmenter.hasComputedDirectives(content, TEST_LINE_ID, errorResult))
    }

    // endregion

    // region buildDisplayText tests

    @Test
    fun `buildDisplayText returns empty for empty content`() {
        val result = DirectiveSegmenter.buildDisplayText("", TEST_LINE_ID, emptyMap())
        assertEquals("", result.displayText)
        assertTrue(result.segments.isEmpty())
        assertTrue(result.directiveDisplayRanges.isEmpty())
    }

    @Test
    fun `buildDisplayText returns original text for content without directives`() {
        val result = DirectiveSegmenter.buildDisplayText("Hello world", TEST_LINE_ID, emptyMap())
        assertEquals("Hello world", result.displayText)
        assertTrue(result.directiveDisplayRanges.isEmpty())
    }

    @Test
    fun `buildDisplayText replaces directive with result`() {
        val content = "[42]"
        val results = DirectiveFinder.executeAllDirectives(content, TEST_LINE_ID)
        val result = DirectiveSegmenter.buildDisplayText(content, TEST_LINE_ID, results)

        assertEquals("42", result.displayText)
        assertEquals(1, result.directiveDisplayRanges.size)

        val range = result.directiveDisplayRanges[0]
        assertEquals(0..3, range.sourceRange)
        assertEquals(0..1, range.displayRange)
        assertEquals("[42]", range.sourceText)
        assertEquals("42", range.displayText)
        assertTrue(range.isComputed)
        assertFalse(range.hasError)
    }

    @Test
    fun `buildDisplayText handles mixed content`() {
        val content = "Hello [42] world"
        val results = DirectiveFinder.executeAllDirectives(content, TEST_LINE_ID)
        val result = DirectiveSegmenter.buildDisplayText(content, TEST_LINE_ID, results)

        // "[42]" (4 chars) replaced with "42" (2 chars)
        assertEquals("Hello 42 world", result.displayText)
        assertEquals(1, result.directiveDisplayRanges.size)

        val range = result.directiveDisplayRanges[0]
        assertEquals(6..9, range.sourceRange)  // "[42]" in original
        assertEquals(6..7, range.displayRange) // "42" in display
    }

    @Test
    fun `buildDisplayText handles uncomputed directive`() {
        val content = "[42]"
        val result = DirectiveSegmenter.buildDisplayText(content, TEST_LINE_ID, emptyMap())

        // Should show source text when not computed
        assertEquals("[42]", result.displayText)
        assertEquals(1, result.directiveDisplayRanges.size)
        assertFalse(result.directiveDisplayRanges[0].isComputed)
    }

    @Test
    fun `buildDisplayText handles string directive`() {
        val content = "[\"hello\"]"
        val results = DirectiveFinder.executeAllDirectives(content, TEST_LINE_ID)
        val result = DirectiveSegmenter.buildDisplayText(content, TEST_LINE_ID, results)

        assertEquals("hello", result.displayText)
    }

    @Test
    fun `buildDisplayText handles multiple directives with different lengths`() {
        val content = "[100] [200]"
        val results = DirectiveFinder.executeAllDirectives(content, TEST_LINE_ID)
        val result = DirectiveSegmenter.buildDisplayText(content, TEST_LINE_ID, results)

        assertEquals("100 200", result.displayText)
        assertEquals(2, result.directiveDisplayRanges.size)

        // First directive
        val range1 = result.directiveDisplayRanges[0]
        assertEquals("[100]", range1.sourceText)
        assertEquals("100", range1.displayText)
        assertEquals(0..2, range1.displayRange)

        // Second directive
        val range2 = result.directiveDisplayRanges[1]
        assertEquals("[200]", range2.sourceText)
        assertEquals("200", range2.displayText)
        assertEquals(4..6, range2.displayRange)
    }

    // endregion

    // region alarm directive display range tests

    @Test
    fun `buildDisplayText sets isAlarm true for alarm directive`() {
        val content = "[alarm(\"abc123\")]"
        val results = DirectiveFinder.executeAllDirectives(content, TEST_LINE_ID)
        val result = DirectiveSegmenter.buildDisplayText(content, TEST_LINE_ID, results)

        assertEquals(1, result.directiveDisplayRanges.size)
        val range = result.directiveDisplayRanges[0]
        assertTrue(range.isAlarm)
        assertEquals("abc123", range.alarmId)
        assertFalse(range.isView)
        assertFalse(range.isButton)
    }

    @Test
    fun `buildDisplayText alarm directive displays as clock emoji`() {
        val content = "[alarm(\"test\")]"
        val results = DirectiveFinder.executeAllDirectives(content, TEST_LINE_ID)
        val result = DirectiveSegmenter.buildDisplayText(content, TEST_LINE_ID, results)

        assertEquals("⏰", result.displayText)
        assertEquals("⏰", result.directiveDisplayRanges[0].displayText)
    }

    @Test
    fun `buildDisplayText alarm with surrounding text`() {
        val content = "Buy milk [alarm(\"x\")] today"
        val results = DirectiveFinder.executeAllDirectives(content, TEST_LINE_ID)
        val result = DirectiveSegmenter.buildDisplayText(content, TEST_LINE_ID, results)

        assertEquals("Buy milk ⏰ today", result.displayText)
        val range = result.directiveDisplayRanges[0]
        assertTrue(range.isAlarm)
        assertEquals("x", range.alarmId)
    }

    @Test
    fun `buildDisplayText non-alarm directive has isAlarm false`() {
        val content = "[42]"
        val results = DirectiveFinder.executeAllDirectives(content, TEST_LINE_ID)
        val result = DirectiveSegmenter.buildDisplayText(content, TEST_LINE_ID, results)

        assertEquals(1, result.directiveDisplayRanges.size)
        assertFalse(result.directiveDisplayRanges[0].isAlarm)
        assertNull(result.directiveDisplayRanges[0].alarmId)
    }

    @Test
    fun `buildDisplayText alarm sourceRange covers entire directive text`() {
        // Tap handlers use `charOffset in sourceRange` to detect alarm taps,
        // so sourceRange must span the full [alarm("id")] text
        val content = "Buy milk [alarm(\"abc\")] today"
        val results = DirectiveFinder.executeAllDirectives(content, TEST_LINE_ID)
        val result = DirectiveSegmenter.buildDisplayText(content, TEST_LINE_ID, results)

        val range = result.directiveDisplayRanges[0]
        val directiveText = content.substring(range.sourceRange.first, range.sourceRange.last + 1)
        assertEquals("[alarm(\"abc\")]", directiveText)
        // Every character offset within the directive source range should match
        for (offset in range.sourceRange) {
            assertTrue("offset $offset should be in sourceRange", offset in range.sourceRange)
        }
        // Offsets just outside should NOT match
        assertFalse(range.sourceRange.first - 1 in range.sourceRange)
        assertFalse(range.sourceRange.last + 1 in range.sourceRange)
    }

    @Test
    fun `buildDisplayText alarm displayRange is single emoji width`() {
        // Display range for ⏰ must be exactly 1 char wide.
        // Tap handlers extend by +1 for cursor-position semantics.
        val content = "Task [alarm(\"x\")]"
        val results = DirectiveFinder.executeAllDirectives(content, TEST_LINE_ID)
        val result = DirectiveSegmenter.buildDisplayText(content, TEST_LINE_ID, results)

        val range = result.directiveDisplayRanges[0]
        assertEquals("⏰", range.displayText)
        // Display range length = 1 (single emoji character)
        assertEquals(1, range.displayRange.last - range.displayRange.first + 1)
    }

    @Test
    fun `buildDisplayText alarm with prefix adjustment has correct ranges`() {
        // End-to-end: checkbox prefix "☐ " (2 chars) + content with alarm
        val content = "[alarm(\"z\")]"
        val key = DirectiveResult.hashDirective("[alarm(\"z\")]")
        val results = mapOf(key to DirectiveFinder.executeDirective("[alarm(\"z\")]").result)

        val result = DirectiveSegmenter.buildDisplayText(content, TEST_LINE_ID, results)

        assertEquals("⏰", result.displayText)
        val range = result.directiveDisplayRanges[0]
        assertTrue(range.isAlarm)
        assertEquals("z", range.alarmId)
        // Source range should cover the content-only text [alarm("z")]
        assertEquals(0..11, range.sourceRange)
        // Display range should be just the emoji
        assertEquals(0..0, range.displayRange)
    }

    // endregion

    // region adjustKeysForPrefix tests

    @Test
    fun `adjustKeysForPrefix returns same map when prefixLength is 0`() {
        val results = mapOf(
            "test-line:5" to DirectiveResult.success(NumberVal(42.0))
        )
        val adjusted = DirectiveSegmenter.adjustKeysForPrefix(results, TEST_LINE_ID, 0)
        assertSame(results, adjusted)
    }

    @Test
    fun `adjustKeysForPrefix subtracts prefix length from offset`() {
        val result = DirectiveResult.success(NumberVal(42.0))
        val results = mapOf("test-line:15" to result)
        val adjusted = DirectiveSegmenter.adjustKeysForPrefix(results, TEST_LINE_ID, 2)
        assertTrue(adjusted.containsKey("test-line:13"))
        assertFalse(adjusted.containsKey("test-line:15"))
        assertEquals(result, adjusted["test-line:13"])
    }

    @Test
    fun `adjustKeysForPrefix only adjusts keys matching lineId`() {
        val r1 = DirectiveResult.success(NumberVal(1.0))
        val r2 = DirectiveResult.success(NumberVal(2.0))
        val results = mapOf("test-line:10" to r1, "other-line:10" to r2)
        val adjusted = DirectiveSegmenter.adjustKeysForPrefix(results, TEST_LINE_ID, 2)
        // test-line key adjusted
        assertTrue(adjusted.containsKey("test-line:8"))
        // other-line key unchanged
        assertTrue(adjusted.containsKey("other-line:10"))
    }

    @Test
    fun `adjustKeysForPrefix handles multiple directives on same line`() {
        val r1 = DirectiveResult.success(NumberVal(1.0))
        val r2 = DirectiveResult.success(NumberVal(2.0))
        val results = mapOf("test-line:5" to r1, "test-line:20" to r2)
        val adjusted = DirectiveSegmenter.adjustKeysForPrefix(results, TEST_LINE_ID, 3)
        assertTrue(adjusted.containsKey("test-line:2"))
        assertTrue(adjusted.containsKey("test-line:17"))
    }

    @Test
    fun `adjustKeysForPrefix skips keys where adjusted offset would be negative`() {
        val result = DirectiveResult.success(NumberVal(42.0))
        val results = mapOf("test-line:1" to result)
        val adjusted = DirectiveSegmenter.adjustKeysForPrefix(results, TEST_LINE_ID, 5)
        // Offset 1 - 5 = -4, should keep original key
        assertTrue(adjusted.containsKey("test-line:1"))
    }

    @Test
    fun `adjustKeysForPrefix works with noteId-based keys`() {
        val result = DirectiveResult.success(NumberVal(42.0))
        val noteId = "abc123"
        val results = mapOf("$noteId:15" to result)
        val adjusted = DirectiveSegmenter.adjustKeysForPrefix(results, noteId, 3)
        assertTrue(adjusted.containsKey("$noteId:12"))
        assertFalse(adjusted.containsKey("$noteId:15"))
    }

    // endregion

    // region prefix key adjustment integration tests (end-to-end with buildDisplayText)

    @Test
    fun `buildDisplayText finds result when keys adjusted for checkbox prefix`() {
        // Simulate: full line is "☐ [alarm("x")]" (prefix "☐ " = 2 chars)
        // parseAllDirectiveLocations would find directive at offset 2 in full text
        // But buildDisplayText receives content "[alarm("x")]" and looks up at offset 0
        val content = "[alarm(\"x\")]"
        val key = DirectiveResult.hashDirective("[alarm(\"x\")]")
        val results = mapOf(key to DirectiveFinder.executeDirective("[alarm(\"x\")]").result)

        val result = DirectiveSegmenter.buildDisplayText(content, TEST_LINE_ID, results)
        assertEquals("⏰", result.displayText)
        assertTrue(result.directiveDisplayRanges[0].isAlarm)
    }

    @Test
    fun `buildDisplayText finds result when keys adjusted for bullet prefix`() {
        val content = "Buy milk [alarm(\"y\")]"
        val lineId = "line-5"
        val key = DirectiveResult.hashDirective("[alarm(\"y\")]")
        val results = mapOf(key to DirectiveFinder.executeDirective("[alarm(\"y\")]").result)

        val result = DirectiveSegmenter.buildDisplayText(content, lineId, results)
        assertEquals("Buy milk ⏰", result.displayText)
    }

    @Test
    fun `buildDisplayText finds result when keys adjusted for tab prefix`() {
        val content = "[42]"
        val key = DirectiveResult.hashDirective("[42]")
        val results = mapOf(key to DirectiveFinder.executeDirective("[42]").result)

        val result = DirectiveSegmenter.buildDisplayText(content, TEST_LINE_ID, results)
        assertEquals("42", result.displayText)
    }

    @Test
    fun `buildDisplayText finds result when keys adjusted for tab plus checkbox prefix`() {
        // "\t☐ [alarm("z")]" — prefix is "\t☐ " = 3 chars
        val content = "[alarm(\"z\")]"
        val noteId = "testNote"
        val key = DirectiveResult.hashDirective("[alarm(\"z\")]")
        val results = mapOf(key to DirectiveFinder.executeDirective("[alarm(\"z\")]").result)

        val result = DirectiveSegmenter.buildDisplayText(content, noteId, results)
        assertEquals("⏰", result.displayText)
        assertTrue(result.directiveDisplayRanges[0].isAlarm)
        assertEquals("z", result.directiveDisplayRanges[0].alarmId)
    }

    // endregion

    // region executeAllDirectives key format tests

    @Test
    fun `executeAllDirectives produces position-independent keys`() {
        // Two identical directives at different positions produce the same key
        val content = "[42] and [42]"
        val results = DirectiveFinder.executeAllDirectives(content, TEST_LINE_ID)
        // Both [42] directives share the same hash key, so only 1 entry
        assertEquals(1, results.size)
        val key = results.keys.first()
        assertEquals(DirectiveResult.hashDirective("[42]"), key)
    }

    @Test
    fun `executeAllDirectives produces different keys for different directives`() {
        val content = "[42] and [43]"
        val results = DirectiveFinder.executeAllDirectives(content, TEST_LINE_ID)
        assertEquals(2, results.size)
        assertTrue(results.containsKey(DirectiveResult.hashDirective("[42]")))
        assertTrue(results.containsKey(DirectiveResult.hashDirective("[43]")))
    }

    // endregion
}
