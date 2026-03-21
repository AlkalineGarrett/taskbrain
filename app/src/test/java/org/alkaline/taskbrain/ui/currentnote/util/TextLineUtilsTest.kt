package org.alkaline.taskbrain.ui.currentnote.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextLineUtilsTest {

    // ==================== findLineStart ====================

    @Test
    fun `findLineStart returns 0 for position 0`() {
        assertEquals(0, TextLineUtils.findLineStart("Hello", 0))
    }

    @Test
    fun `findLineStart returns 0 for first line`() {
        assertEquals(0, TextLineUtils.findLineStart("Hello\nWorld", 3))
    }

    @Test
    fun `findLineStart returns correct position for second line`() {
        assertEquals(6, TextLineUtils.findLineStart("Hello\nWorld", 8))
    }

    @Test
    fun `findLineStart returns correct position for third line`() {
        assertEquals(12, TextLineUtils.findLineStart("Line1\nLine2\nLine3", 14))
    }

    @Test
    fun `findLineStart at newline returns start of next line`() {
        assertEquals(0, TextLineUtils.findLineStart("Hello\nWorld", 5))
    }

    // ==================== findLineEnd ====================

    @Test
    fun `findLineEnd returns text length for single line`() {
        assertEquals(5, TextLineUtils.findLineEnd("Hello", 0))
    }

    @Test
    fun `findLineEnd returns position before newline`() {
        assertEquals(5, TextLineUtils.findLineEnd("Hello\nWorld", 3))
    }

    @Test
    fun `findLineEnd returns text length for last line`() {
        assertEquals(11, TextLineUtils.findLineEnd("Hello\nWorld", 8))
    }

    @Test
    fun `findLineEnd at newline returns same position`() {
        assertEquals(5, TextLineUtils.findLineEnd("Hello\nWorld", 5))
    }

    // ==================== getLineBounds ====================

    @Test
    fun `getLineBounds returns correct bounds for first line`() {
        val bounds = TextLineUtils.getLineBounds("Hello\nWorld", 3)
        assertEquals(LineBounds(0, 5), bounds)
    }

    @Test
    fun `getLineBounds returns correct bounds for second line`() {
        val bounds = TextLineUtils.getLineBounds("Hello\nWorld", 8)
        assertEquals(LineBounds(6, 11), bounds)
    }

    @Test
    fun `getLineBounds returns correct bounds for single line`() {
        val bounds = TextLineUtils.getLineBounds("Hello", 3)
        assertEquals(LineBounds(0, 5), bounds)
    }

    // ==================== getSelectionLineBounds ====================

    @Test
    fun `getSelectionLineBounds for collapsed selection`() {
        val bounds = TextLineUtils.getSelectionLineBounds("Hello\nWorld", 3, 3)
        assertEquals(LineBounds(0, 5), bounds)
    }

    @Test
    fun `getSelectionLineBounds for single line selection`() {
        val bounds = TextLineUtils.getSelectionLineBounds("Hello\nWorld", 1, 4)
        assertEquals(LineBounds(0, 5), bounds)
    }

    @Test
    fun `getSelectionLineBounds for multi-line selection`() {
        val bounds = TextLineUtils.getSelectionLineBounds("Line1\nLine2\nLine3", 3, 14)
        assertEquals(LineBounds(0, 17), bounds)
    }

    @Test
    fun `getSelectionLineBounds when selection ends at line start`() {
        // Selection from position 3 to 6 (where 6 is start of "World")
        val bounds = TextLineUtils.getSelectionLineBounds("Hello\nWorld", 3, 6)
        assertEquals(LineBounds(0, 5), bounds)
    }

    // ==================== extractIndentation ====================

    @Test
    fun `extractIndentation returns empty for no tabs`() {
        assertEquals("", TextLineUtils.extractIndentation("Hello"))
    }

    @Test
    fun `extractIndentation returns single tab`() {
        assertEquals("\t", TextLineUtils.extractIndentation("\tHello"))
    }

    @Test
    fun `extractIndentation returns multiple tabs`() {
        assertEquals("\t\t\t", TextLineUtils.extractIndentation("\t\t\tHello"))
    }

    @Test
    fun `extractIndentation stops at non-tab`() {
        assertEquals("\t", TextLineUtils.extractIndentation("\t • Hello"))
    }

    // ==================== removeIndentation ====================

    @Test
    fun `removeIndentation removes single tab`() {
        assertEquals("Hello", TextLineUtils.removeIndentation("\tHello"))
    }

    @Test
    fun `removeIndentation removes multiple tabs`() {
        assertEquals("Hello", TextLineUtils.removeIndentation("\t\t\tHello"))
    }

    @Test
    fun `removeIndentation returns same for no tabs`() {
        assertEquals("Hello", TextLineUtils.removeIndentation("Hello"))
    }

    // ==================== parseLine ====================

    @Test
    fun `parseLine parses plain text`() {
        val info = TextLineUtils.parseLine("Hello")
        assertEquals("", info.indentation)
        assertNull(info.prefix)
        assertEquals("Hello", info.content)
    }

    @Test
    fun `parseLine parses bullet line`() {
        val info = TextLineUtils.parseLine("• Item")
        assertEquals("", info.indentation)
        assertEquals("• ", info.prefix)
        assertEquals("Item", info.content)
    }

    @Test
    fun `parseLine parses indented bullet line`() {
        val info = TextLineUtils.parseLine("\t\t• Item")
        assertEquals("\t\t", info.indentation)
        assertEquals("• ", info.prefix)
        assertEquals("Item", info.content)
    }

    @Test
    fun `parseLine parses checkbox line`() {
        val info = TextLineUtils.parseLine("☐ Task")
        assertEquals("", info.indentation)
        assertEquals("☐ ", info.prefix)
        assertEquals("Task", info.content)
    }

    @Test
    fun `parseLine preserves full line`() {
        val line = "\t• Item"
        val info = TextLineUtils.parseLine(line)
        assertEquals(line, info.fullLine)
    }

    // ==================== getLineContent ====================

    @Test
    fun `getLineContent returns first line`() {
        assertEquals("Hello", TextLineUtils.getLineContent("Hello\nWorld", 3))
    }

    @Test
    fun `getLineContent returns second line`() {
        assertEquals("World", TextLineUtils.getLineContent("Hello\nWorld", 8))
    }

    // ==================== isAtLineStart ====================

    @Test
    fun `isAtLineStart returns true at position 0`() {
        assertTrue(TextLineUtils.isAtLineStart("Hello", 0))
    }

    @Test
    fun `isAtLineStart returns true after newline`() {
        assertTrue(TextLineUtils.isAtLineStart("Hello\nWorld", 6))
    }

    @Test
    fun `isAtLineStart returns false in middle of line`() {
        assertFalse(TextLineUtils.isAtLineStart("Hello", 3))
    }

    // ==================== isAtLineEnd ====================

    @Test
    fun `isAtLineEnd returns true at text end`() {
        assertTrue(TextLineUtils.isAtLineEnd("Hello", 5))
    }

    @Test
    fun `isAtLineEnd returns true at newline`() {
        assertTrue(TextLineUtils.isAtLineEnd("Hello\nWorld", 5))
    }

    @Test
    fun `isAtLineEnd returns false in middle of line`() {
        assertFalse(TextLineUtils.isAtLineEnd("Hello", 3))
    }

    // ==================== isFullLineSelection ====================

    @Test
    fun `isFullLineSelection returns false for collapsed selection`() {
        assertFalse(TextLineUtils.isFullLineSelection("Hello", 0, 0))
    }

    @Test
    fun `isFullLineSelection returns true for full line`() {
        assertTrue(TextLineUtils.isFullLineSelection("Hello\nWorld", 0, 5))
    }

    @Test
    fun `isFullLineSelection returns true for full second line`() {
        assertTrue(TextLineUtils.isFullLineSelection("Hello\nWorld", 6, 11))
    }

    @Test
    fun `isFullLineSelection returns false for partial line`() {
        assertFalse(TextLineUtils.isFullLineSelection("Hello", 1, 4))
    }

    @Test
    fun `isFullLineSelection returns true for multiple full lines`() {
        assertTrue(TextLineUtils.isFullLineSelection("Line1\nLine2\nLine3", 0, 11))
    }

    // ==================== getLinesInSelection ====================

    @Test
    fun `getLinesInSelection returns single line for collapsed selection`() {
        val lines = TextLineUtils.getLinesInSelection("Hello\nWorld", 3, 3)
        assertEquals(listOf("Hello"), lines)
    }

    @Test
    fun `getLinesInSelection returns multiple lines`() {
        val lines = TextLineUtils.getLinesInSelection("Line1\nLine2\nLine3", 3, 14)
        assertEquals(listOf("Line1", "Line2", "Line3"), lines)
    }

    // ==================== processLinesInSelection ====================

    @Test
    fun `processLinesInSelection transforms single line`() {
        val result = TextLineUtils.processLinesInSelection("Hello", 0, 5) { "• $it" }
        assertEquals("• Hello", result.text)
        assertEquals(0, result.newSelStart)
        assertEquals(7, result.newSelEnd)
    }

    @Test
    fun `processLinesInSelection transforms multiple lines`() {
        val result = TextLineUtils.processLinesInSelection(
            "Line1\nLine2\nLine3",
            0, 17
        ) { "• $it" }
        assertEquals("• Line1\n• Line2\n• Line3", result.text)
    }

    @Test
    fun `processLinesInSelection preserves surrounding text`() {
        val result = TextLineUtils.processLinesInSelection(
            "Before\nLine\nAfter",
            7, 11
        ) { "• $it" }
        assertEquals("Before\n• Line\nAfter", result.text)
    }

    // ==================== getLineIndex ====================

    @Test
    fun `getLineIndex returns 0 for first line`() {
        assertEquals(0, TextLineUtils.getLineIndex("Hello\nWorld", 3))
    }

    @Test
    fun `getLineIndex returns 1 for second line`() {
        assertEquals(1, TextLineUtils.getLineIndex("Hello\nWorld", 8))
    }

    @Test
    fun `getLineIndex returns 2 for third line`() {
        assertEquals(2, TextLineUtils.getLineIndex("Line1\nLine2\nLine3", 14))
    }

    @Test
    fun `getLineIndex at newline is still on previous line`() {
        assertEquals(0, TextLineUtils.getLineIndex("Hello\nWorld", 5))
    }

    // ==================== getLineStartOffset ====================

    @Test
    fun `getLineStartOffset returns 0 for line 0`() {
        assertEquals(0, TextLineUtils.getLineStartOffset("Hello\nWorld", 0))
    }

    @Test
    fun `getLineStartOffset returns correct offset for line 1`() {
        assertEquals(6, TextLineUtils.getLineStartOffset("Hello\nWorld", 1))
    }

    @Test
    fun `getLineStartOffset returns correct offset for line 2`() {
        assertEquals(12, TextLineUtils.getLineStartOffset("Line1\nLine2\nLine3", 2))
    }

    // ==================== trimLineForAlarm ====================

    @Test
    fun `trimLineForAlarm returns plain text unchanged`() {
        assertEquals("Buy groceries", TextLineUtils.trimLineForAlarm("Buy groceries"))
    }

    @Test
    fun `trimLineForAlarm removes leading tabs`() {
        assertEquals("Buy groceries", TextLineUtils.trimLineForAlarm("\t\tBuy groceries"))
    }

    @Test
    fun `trimLineForAlarm removes bullet prefix`() {
        assertEquals("Buy groceries", TextLineUtils.trimLineForAlarm("• Buy groceries"))
    }

    @Test
    fun `trimLineForAlarm removes unchecked checkbox prefix`() {
        assertEquals("Buy groceries", TextLineUtils.trimLineForAlarm("☐ Buy groceries"))
    }

    @Test
    fun `trimLineForAlarm removes checked checkbox prefix`() {
        assertEquals("Buy groceries", TextLineUtils.trimLineForAlarm("☑ Buy groceries"))
    }

    @Test
    fun `trimLineForAlarm removes indentation and bullet`() {
        assertEquals("Buy groceries", TextLineUtils.trimLineForAlarm("\t• Buy groceries"))
    }

    @Test
    fun `trimLineForAlarm removes trailing alarm symbol`() {
        assertEquals("Buy groceries", TextLineUtils.trimLineForAlarm("Buy groceries ⏰"))
    }

    @Test
    fun `trimLineForAlarm removes alarm symbol without preceding space`() {
        assertEquals("Buy groceries", TextLineUtils.trimLineForAlarm("Buy groceries⏰"))
    }

    @Test
    fun `trimLineForAlarm removes both prefix and alarm symbol`() {
        assertEquals("Buy groceries", TextLineUtils.trimLineForAlarm("\t\t• Buy groceries ⏰"))
    }

    @Test
    fun `trimLineForAlarm handles checkbox with alarm symbol`() {
        assertEquals("Call mom", TextLineUtils.trimLineForAlarm("☐ Call mom ⏰"))
    }

    @Test
    fun `trimLineForAlarm trims extra whitespace`() {
        assertEquals("Buy groceries", TextLineUtils.trimLineForAlarm("  Buy groceries  "))
    }

    @Test
    fun `trimLineForAlarm handles empty line`() {
        assertEquals("", TextLineUtils.trimLineForAlarm(""))
    }

    @Test
    fun `trimLineForAlarm handles line with only prefix`() {
        assertEquals("", TextLineUtils.trimLineForAlarm("• "))
    }

    @Test
    fun `trimLineForAlarm handles line with only alarm symbol`() {
        assertEquals("", TextLineUtils.trimLineForAlarm("⏰"))
    }

    @Test
    fun `trimLineForAlarm removes alarm directive`() {
        assertEquals("Buy groceries", TextLineUtils.trimLineForAlarm("Buy groceries [alarm(\"abc123\")]"))
    }

    @Test
    fun `trimLineForAlarm removes alarm directive with prefix`() {
        assertEquals("Call mom", TextLineUtils.trimLineForAlarm("\t• Call mom [alarm(\"xyz\")]"))
    }

    @Test
    fun `trimLineForAlarm removes alarm directive without space`() {
        assertEquals("Buy groceries", TextLineUtils.trimLineForAlarm("Buy groceries[alarm(\"abc\")]"))
    }

    @Test
    fun `trimLineForAlarm handles line with only alarm directive`() {
        assertEquals("", TextLineUtils.trimLineForAlarm("[alarm(\"abc\")]"))
    }
}
