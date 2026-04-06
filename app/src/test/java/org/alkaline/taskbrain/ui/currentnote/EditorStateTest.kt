package org.alkaline.taskbrain.ui.currentnote

import org.alkaline.taskbrain.ui.currentnote.selection.EditorSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorStateTest {

    // ==================== LineState.extractPrefix ====================

    @Test
    fun `extractPrefix returns empty for plain text`() {
        assertEquals("", LineState.extractPrefix("hello world"))
    }

    @Test
    fun `extractPrefix returns bullet prefix`() {
        assertEquals("• ", LineState.extractPrefix("• hello"))
    }

    @Test
    fun `extractPrefix returns unchecked checkbox prefix`() {
        assertEquals("☐ ", LineState.extractPrefix("☐ task"))
    }

    @Test
    fun `extractPrefix returns checked checkbox prefix`() {
        assertEquals("☑ ", LineState.extractPrefix("☑ done"))
    }

    @Test
    fun `extractPrefix returns tabs and bullet`() {
        assertEquals("\t• ", LineState.extractPrefix("\t• indented item"))
    }

    @Test
    fun `extractPrefix returns multiple tabs and checkbox`() {
        assertEquals("\t\t☐ ", LineState.extractPrefix("\t\t☐ deeply indented"))
    }

    @Test
    fun `extractPrefix returns only tabs when no bullet`() {
        assertEquals("\t\t", LineState.extractPrefix("\t\tindented plain"))
    }

    @Test
    fun `extractPrefix handles empty string`() {
        assertEquals("", LineState.extractPrefix(""))
    }

    @Test
    fun `extractPrefix handles only tabs`() {
        assertEquals("\t\t", LineState.extractPrefix("\t\t"))
    }

    @Test
    fun `extractPrefix handles only bullet`() {
        assertEquals("• ", LineState.extractPrefix("• "))
    }

    // ==================== LineState content and prefix ====================

    @Test
    fun `LineState splits prefix and content correctly for bullet`() {
        val line = LineState("• hello world")
        assertEquals("• ", line.prefix)
        assertEquals("hello world", line.content)
    }

    @Test
    fun `LineState splits prefix and content for indented checkbox`() {
        val line = LineState("\t☐ my task")
        assertEquals("\t☐ ", line.prefix)
        assertEquals("my task", line.content)
    }

    @Test
    fun `LineState handles plain text`() {
        val line = LineState("plain text")
        assertEquals("", line.prefix)
        assertEquals("plain text", line.content)
    }

    @Test
    fun `LineState handles empty string`() {
        val line = LineState("")
        assertEquals("", line.prefix)
        assertEquals("", line.content)
    }

    // ==================== LineState.updateContent ====================

    @Test
    fun `updateContent preserves prefix`() {
        val line = LineState("• original")
        line.updateContent("modified", 4)
        assertEquals("• modified", line.text)
        assertEquals("• ", line.prefix)
        assertEquals("modified", line.content)
    }

    @Test
    fun `updateContent updates cursor correctly`() {
        val line = LineState("• hello", 4) // cursor after "he"
        line.updateContent("world", 2)
        assertEquals(4, line.cursorPosition) // prefix(2) + content cursor(2)
        assertEquals(2, line.contentCursorPosition)
    }

    @Test
    fun `updateContent clamps cursor to content length`() {
        val line = LineState("• hi")
        line.updateContent("x", 100)
        assertEquals(3, line.cursorPosition) // prefix(2) + content length(1)
    }

    // ==================== LineState.updateFull ====================

    @Test
    fun `updateFull replaces entire text`() {
        val line = LineState("• original")
        line.updateFull("☐ new task", 5)
        assertEquals("☐ new task", line.text)
        assertEquals("☐ ", line.prefix)
        assertEquals("new task", line.content)
        assertEquals(5, line.cursorPosition)
    }

    // ==================== LineState.indent ====================

    @Test
    fun `indent adds tab at beginning`() {
        val line = LineState("• item", 3)
        line.indent()
        assertEquals("\t• item", line.text)
        assertEquals(4, line.cursorPosition) // cursor moved by 1
    }

    @Test
    fun `indent on already indented line`() {
        val line = LineState("\t• item")
        line.indent()
        assertEquals("\t\t• item", line.text)
    }

    // ==================== LineState.unindent ====================

    @Test
    fun `unindent removes leading tab`() {
        val line = LineState("\t• item", 4)
        val result = line.unindent()
        assertTrue(result)
        assertEquals("• item", line.text)
        assertEquals(3, line.cursorPosition) // cursor moved back by 1
    }

    @Test
    fun `unindent returns false when no leading tab`() {
        val line = LineState("• item")
        val result = line.unindent()
        assertFalse(result)
        assertEquals("• item", line.text)
    }

    @Test
    fun `unindent clamps cursor to zero`() {
        val line = LineState("\t• item", 0)
        line.unindent()
        assertEquals(0, line.cursorPosition)
    }

    // ==================== LineState.toggleBullet ====================

    @Test
    fun `toggleBullet adds bullet to plain text`() {
        val line = LineState("item")
        line.toggleBullet()
        assertEquals("• item", line.text)
    }

    @Test
    fun `toggleBullet removes bullet`() {
        val line = LineState("• item")
        line.toggleBullet()
        assertEquals("item", line.text)
    }

    @Test
    fun `toggleBullet converts checkbox to bullet`() {
        val line = LineState("☐ task")
        line.toggleBullet()
        assertEquals("• task", line.text)
    }

    @Test
    fun `toggleBullet converts checked checkbox to bullet`() {
        val line = LineState("☑ done")
        line.toggleBullet()
        assertEquals("• done", line.text)
    }

    @Test
    fun `toggleBullet preserves indentation`() {
        val line = LineState("\t\titem")
        line.toggleBullet()
        assertEquals("\t\t• item", line.text)
    }

    // ==================== LineState.toggleCheckbox ====================

    @Test
    fun `toggleCheckbox adds unchecked to plain text`() {
        val line = LineState("task")
        line.toggleCheckbox()
        assertEquals("☐ task", line.text)
    }

    @Test
    fun `toggleCheckbox checks unchecked`() {
        val line = LineState("☐ task")
        line.toggleCheckbox()
        assertEquals("☑ task", line.text)
    }

    @Test
    fun `toggleCheckbox removes checked`() {
        val line = LineState("☑ done")
        line.toggleCheckbox()
        assertEquals("done", line.text)
    }

    @Test
    fun `toggleCheckbox converts bullet to checkbox`() {
        val line = LineState("• item")
        line.toggleCheckbox()
        assertEquals("☐ item", line.text)
    }

    @Test
    fun `toggleCheckbox preserves indentation`() {
        val line = LineState("\ttask")
        line.toggleCheckbox()
        assertEquals("\t☐ task", line.text)
    }

    // ==================== EditorSelection ====================

    @Test
    fun `EditorSelection min and max with start less than end`() {
        val sel = EditorSelection(5, 10)
        assertEquals(5, sel.min)
        assertEquals(10, sel.max)
    }

    @Test
    fun `EditorSelection min and max with start greater than end`() {
        val sel = EditorSelection(10, 5)
        assertEquals(5, sel.min)
        assertEquals(10, sel.max)
    }

    @Test
    fun `EditorSelection isCollapsed when start equals end`() {
        val sel = EditorSelection(5, 5)
        assertTrue(sel.isCollapsed)
        assertFalse(sel.hasSelection)
    }

    @Test
    fun `EditorSelection hasSelection when not collapsed`() {
        val sel = EditorSelection(5, 10)
        assertFalse(sel.isCollapsed)
        assertTrue(sel.hasSelection)
    }

    @Test
    fun `EditorSelection None has no selection`() {
        assertFalse(EditorSelection.None.hasSelection)
    }

    // ==================== EditorState.getLineStartOffset ====================

    @Test
    fun `getLineStartOffset for first line is zero`() {
        val state = EditorState()
        state.updateFromText("line1\nline2\nline3")
        assertEquals(0, state.getLineStartOffset(0))
    }

    @Test
    fun `getLineStartOffset for second line`() {
        val state = EditorState()
        state.updateFromText("line1\nline2\nline3")
        // "line1" (5) + newline (1) = 6
        assertEquals(6, state.getLineStartOffset(1))
    }

    @Test
    fun `getLineStartOffset for third line`() {
        val state = EditorState()
        state.updateFromText("line1\nline2\nline3")
        // "line1" (5) + newline (1) + "line2" (5) + newline (1) = 12
        assertEquals(12, state.getLineStartOffset(2))
    }

    @Test
    fun `getLineStartOffset with varying line lengths`() {
        val state = EditorState()
        state.updateFromText("a\nbb\nccc")
        assertEquals(0, state.getLineStartOffset(0))
        assertEquals(2, state.getLineStartOffset(1)) // "a" + newline
        assertEquals(5, state.getLineStartOffset(2)) // "a\nbb\n"
    }

    // ==================== EditorState.getLineAndLocalOffset ====================

    @Test
    fun `getLineAndLocalOffset at start of first line`() {
        val state = EditorState()
        state.updateFromText("line1\nline2")
        val (lineIndex, localOffset) = state.getLineAndLocalOffset(0)
        assertEquals(0, lineIndex)
        assertEquals(0, localOffset)
    }

    @Test
    fun `getLineAndLocalOffset in middle of first line`() {
        val state = EditorState()
        state.updateFromText("line1\nline2")
        val (lineIndex, localOffset) = state.getLineAndLocalOffset(3)
        assertEquals(0, lineIndex)
        assertEquals(3, localOffset)
    }

    @Test
    fun `getLineAndLocalOffset at end of first line`() {
        val state = EditorState()
        state.updateFromText("line1\nline2")
        val (lineIndex, localOffset) = state.getLineAndLocalOffset(5)
        assertEquals(0, lineIndex)
        assertEquals(5, localOffset)
    }

    @Test
    fun `getLineAndLocalOffset at start of second line`() {
        val state = EditorState()
        state.updateFromText("line1\nline2")
        val (lineIndex, localOffset) = state.getLineAndLocalOffset(6)
        assertEquals(1, lineIndex)
        assertEquals(0, localOffset)
    }

    @Test
    fun `getLineAndLocalOffset in middle of second line`() {
        val state = EditorState()
        state.updateFromText("line1\nline2")
        val (lineIndex, localOffset) = state.getLineAndLocalOffset(8)
        assertEquals(1, lineIndex)
        assertEquals(2, localOffset)
    }

    @Test
    fun `getLineAndLocalOffset at end of text`() {
        val state = EditorState()
        state.updateFromText("line1\nline2")
        val (lineIndex, localOffset) = state.getLineAndLocalOffset(11)
        assertEquals(1, lineIndex)
        assertEquals(5, localOffset)
    }

    @Test
    fun `getLineAndLocalOffset beyond text length`() {
        val state = EditorState()
        state.updateFromText("line1\nline2")
        val (lineIndex, localOffset) = state.getLineAndLocalOffset(100)
        assertEquals(1, lineIndex)
        assertEquals(5, localOffset) // Clamped to end
    }

    // ==================== EditorState.getLineSelection ====================

    @Test
    fun `getLineSelection returns null when no selection`() {
        val state = EditorState()
        state.updateFromText("line1\nline2")
        assertNull(state.getLineSelection(0))
    }

    @Test
    fun `getLineSelection for line fully within selection`() {
        val state = EditorState()
        state.updateFromText("line1\nline2\nline3")
        state.setSelection(0, 17) // Select all
        val selection = state.getLineSelection(1)
        assertEquals(0 until 5, selection) // Entire "line2"
    }

    @Test
    fun `getLineSelection for line partially selected at start`() {
        val state = EditorState()
        state.updateFromText("line1\nline2")
        state.setSelection(0, 3) // Select "lin" from first line
        val selection = state.getLineSelection(0)
        assertEquals(0 until 3, selection)
    }

    @Test
    fun `getLineSelection for line partially selected at end`() {
        val state = EditorState()
        state.updateFromText("line1\nline2")
        state.setSelection(3, 6) // Select "e1" + newline area
        val selection = state.getLineSelection(0)
        assertEquals(3 until 5, selection)
    }

    @Test
    fun `getLineSelection returns null for unselected line`() {
        val state = EditorState()
        state.updateFromText("line1\nline2\nline3")
        state.setSelection(0, 3) // Select only in first line
        assertNull(state.getLineSelection(2)) // Third line not selected
    }

    @Test
    fun `getLineSelection spanning multiple lines`() {
        val state = EditorState()
        state.updateFromText("line1\nline2\nline3")
        state.setSelection(3, 14) // From "e1" to "li" of line3

        val sel0 = state.getLineSelection(0)
        val sel1 = state.getLineSelection(1)
        val sel2 = state.getLineSelection(2)

        assertEquals(3 until 5, sel0) // "e1" from line1
        assertEquals(0 until 5, sel1) // All of "line2"
        assertEquals(0 until 2, sel2) // "li" from line3
    }

    // ==================== EditorState.getSelectedText ====================

    @Test
    fun `getSelectedText returns empty when no selection`() {
        val state = EditorState()
        state.updateFromText("hello world")
        assertEquals("", state.getSelectedText())
    }

    @Test
    fun `getSelectedText returns selected text`() {
        val state = EditorState()
        state.updateFromText("hello world")
        state.setSelection(0, 5)
        assertEquals("hello", state.getSelectedText())
    }

    @Test
    fun `getSelectedText with reversed selection`() {
        val state = EditorState()
        state.updateFromText("hello world")
        state.setSelection(5, 0) // Reversed
        assertEquals("hello", state.getSelectedText())
    }

    @Test
    fun `getSelectedText spanning lines`() {
        val state = EditorState()
        state.updateFromText("hello\nworld")
        state.setSelection(3, 9) // "lo\nwor"
        assertEquals("lo\nwor", state.getSelectedText())
    }

    @Test
    fun `getSelectedText extends to include newline for full line selection`() {
        val state = EditorState()
        state.updateFromText("line1\nline2\nline3")
        // Select "line2" (positions 6-11, ends right before newline)
        state.setSelection(6, 11)
        // Should extend to include the trailing newline
        assertEquals("line2\n", state.getSelectedText())
    }

    @Test
    fun `getSelectedText extends for multiple full lines`() {
        val state = EditorState()
        state.updateFromText("line1\nline2\nline3\nline4")
        // Select "line2\nline3" (positions 6-17, ends right before newline)
        state.setSelection(6, 17)
        // Should extend to include the trailing newline
        assertEquals("line2\nline3\n", state.getSelectedText())
    }

    @Test
    fun `getSelectedText does not extend when first line not fully selected`() {
        val state = EditorState()
        state.updateFromText("line1\nline2\nline3")
        // Select "ine2" (positions 7-11, starts mid-line)
        state.setSelection(7, 11)
        // Should NOT extend because first line is not fully selected
        assertEquals("ine2", state.getSelectedText())
    }

    @Test
    fun `getSelectedText does not extend when selection ends mid-line`() {
        val state = EditorState()
        state.updateFromText("line1\nline2\nline3")
        // Select "line2\nlin" (positions 6-15, ends mid-line)
        state.setSelection(6, 15)
        // Should NOT extend because it doesn't end at line boundary
        assertEquals("line2\nlin", state.getSelectedText())
    }

    @Test
    fun `getSelectedText does not extend at end of document`() {
        val state = EditorState()
        state.updateFromText("line1\nline2")
        // Select "line2" (positions 6-11, at end of document)
        state.setSelection(6, 11)
        // Should NOT extend because there's no newline to include
        assertEquals("line2", state.getSelectedText())
    }

    // ==================== EditorState.text ====================

    @Test
    fun `text property joins lines with newlines`() {
        val state = EditorState()
        state.updateFromText("line1\nline2\nline3")
        assertEquals("line1\nline2\nline3", state.text)
    }

    @Test
    fun `text property for single line`() {
        val state = EditorState()
        state.updateFromText("single line")
        assertEquals("single line", state.text)
    }

    @Test
    fun `text property for empty editor`() {
        val state = EditorState()
        assertEquals("", state.text)
    }

    // ==================== EditorState.updateFromText ====================

    @Test
    fun `updateFromText creates lines correctly`() {
        val state = EditorState()
        state.updateFromText("line1\nline2\nline3")
        assertEquals(3, state.lines.size)
        assertEquals("line1", state.lines[0].text)
        assertEquals("line2", state.lines[1].text)
        assertEquals("line3", state.lines[2].text)
    }

    @Test
    fun `updateFromText handles empty lines`() {
        val state = EditorState()
        state.updateFromText("line1\n\nline3")
        assertEquals(3, state.lines.size)
        assertEquals("", state.lines[1].text)
    }

    @Test
    fun `updateFromText clamps focusedLineIndex`() {
        val state = EditorState()
        state.updateFromText("line1\nline2\nline3")
        state.focusedLineIndex = 2
        state.updateFromText("single line")
        assertEquals(0, state.focusedLineIndex)
    }

    // ==================== EditorState.deleteSelection ====================

    @Test
    fun `deleteSelection removes selected text`() {
        val state = EditorState()
        state.updateFromText("hello world")
        state.setSelection(0, 5)
        val newCursor = state.deleteSelectionInternal()
        assertEquals(" world", state.text)
        assertEquals(0, newCursor)
    }

    @Test
    fun `deleteSelection with selection in middle`() {
        val state = EditorState()
        state.updateFromText("hello world")
        state.setSelection(5, 11)
        state.deleteSelectionInternal()
        assertEquals("hello", state.text)
    }

    @Test
    fun `deleteSelection spanning multiple lines`() {
        val state = EditorState()
        state.updateFromText("line1\nline2\nline3")
        state.setSelection(3, 14) // "e1\nline2\nli"
        state.deleteSelectionInternal()
        assertEquals("linne3", state.text)
    }

    @Test
    fun `deleteSelection returns -1 when no selection`() {
        val state = EditorState()
        state.updateFromText("hello world")
        val result = state.deleteSelectionInternal()
        assertEquals(-1, result)
        assertEquals("hello world", state.text)
    }

    @Test
    fun `deleteSelection clears selection after delete`() {
        val state = EditorState()
        state.updateFromText("hello world")
        state.setSelection(0, 5)
        state.deleteSelectionInternal()
        assertFalse(state.hasSelection)
    }

    @Test
    fun `deleteSelection removes empty lines created by deletion`() {
        val state = EditorState()
        state.updateFromText("line1\nhello\nline3")
        // Select "hello" (the entire content of line2)
        state.setSelection(6, 11)
        state.deleteSelectionInternal()
        // The empty line should be removed
        assertEquals("line1\nline3", state.text)
        assertEquals(2, state.lines.size)
    }

    @Test
    fun `deleteSelection keeps last line even if empty`() {
        val state = EditorState()
        state.updateFromText("line1\nhello")
        // Select "hello" (the entire content of line2, which is the last line)
        state.setSelection(6, 11)
        state.deleteSelectionInternal()
        // The last line should be kept even though it's empty
        assertEquals("line1\n", state.text)
        assertEquals(2, state.lines.size)
    }

    @Test
    fun `deleteSelection removes empty line at cursor position only`() {
        val state = EditorState()
        state.updateFromText("line1\na\nb\nline4")
        // Select "a\nb" - this creates one empty line at the join point
        state.setSelection(6, 9)
        state.deleteSelectionInternal()
        // The empty line at cursor should be removed
        assertEquals("line1\nline4", state.text)
        assertEquals(2, state.lines.size)
    }

    @Test
    fun `deleteSelection preserves pre-existing empty lines`() {
        val state = EditorState()
        // Document with an intentional empty line (spacer)
        state.updateFromText("line1\n\nline3\nhello\nline5")
        // Select "hello" (line 4 content, positions 13-18)
        state.setSelection(13, 18)
        state.deleteSelectionInternal()
        // The pre-existing empty line at index 1 should be preserved
        // Only the empty line created at the cursor position should be removed
        assertEquals("line1\n\nline3\nline5", state.text)
        assertEquals(4, state.lines.size)
        assertEquals("", state.lines[1].text) // Pre-existing empty line preserved
    }

    @Test
    fun `deleteSelection extends to newline for full line selection`() {
        val state = EditorState()
        state.updateFromText("line1\nline2\nline3")
        // Select "line2" (positions 6-11, a full line)
        state.setSelection(6, 11)
        state.deleteSelectionInternal()
        // Should delete "line2\n" (including newline), leaving no empty line
        assertEquals("line1\nline3", state.text)
        assertEquals(2, state.lines.size)
    }

    @Test
    fun `deleteSelection extends for multiple full lines`() {
        val state = EditorState()
        state.updateFromText("line1\nline2\nline3\nline4")
        // Select "line2\nline3" (positions 6-17, two full lines)
        state.setSelection(6, 17)
        state.deleteSelectionInternal()
        // Should delete "line2\nline3\n" (including trailing newline)
        assertEquals("line1\nline4", state.text)
        assertEquals(2, state.lines.size)
    }

    @Test
    fun `deleteSelection does not extend when first line partial`() {
        val state = EditorState()
        state.updateFromText("line1\nline2\nline3")
        // Select "ine2" (positions 7-11, partial first line)
        state.setSelection(7, 11)
        state.deleteSelectionInternal()
        // Should NOT extend, leaves "l" on line 2, then empty line cleanup removes it
        assertEquals("line1\nl\nline3", state.text)
    }

    // ==================== EditorState.replaceSelection ====================

    @Test
    fun `replaceSelection replaces selected text`() {
        val state = EditorState()
        state.updateFromText("hello world")
        state.setSelection(0, 5)
        val newCursor = state.replaceSelectionInternal("goodbye")
        assertEquals("goodbye world", state.text)
        assertEquals(7, newCursor) // Position after "goodbye"
    }

    @Test
    fun `replaceSelection with empty replacement deletes selection`() {
        val state = EditorState()
        state.updateFromText("hello world")
        state.setSelection(5, 11)
        state.replaceSelectionInternal("")
        assertEquals("hello", state.text)
    }

    @Test
    fun `replaceSelection spanning multiple lines`() {
        val state = EditorState()
        state.updateFromText("line1\nline2\nline3")
        state.setSelection(0, 12) // "line1\nline2\n"
        state.replaceSelectionInternal("new\n")
        assertEquals("new\nline3", state.text)
    }

    @Test
    fun `replaceSelection clears selection after replace`() {
        val state = EditorState()
        state.updateFromText("hello world")
        state.setSelection(0, 5)
        state.replaceSelectionInternal("hi")
        assertFalse(state.hasSelection)
    }

    @Test
    fun `replaceSelection removes empty lines when replacing with empty string`() {
        val state = EditorState()
        state.updateFromText("line1\nhello\nline3")
        // Select "hello" (the entire content of line2)
        state.setSelection(6, 11)
        state.replaceSelectionInternal("")
        // The empty line should be removed
        assertEquals("line1\nline3", state.text)
        assertEquals(2, state.lines.size)
    }

    @Test
    fun `replaceSelection keeps last line even if empty`() {
        val state = EditorState()
        state.updateFromText("line1\nhello")
        // Select "hello" (the entire content of last line)
        state.setSelection(6, 11)
        state.replaceSelectionInternal("")
        // Last line kept even though empty
        assertEquals("line1\n", state.text)
        assertEquals(2, state.lines.size)
    }

    @Test
    fun `replaceSelection preserves pre-existing empty lines`() {
        val state = EditorState()
        // Document with an intentional empty line (spacer)
        state.updateFromText("line1\n\nline3\nhello\nline5")
        // Select "hello" and replace with empty string
        state.setSelection(13, 18)
        state.replaceSelectionInternal("")
        // The pre-existing empty line should be preserved
        assertEquals("line1\n\nline3\nline5", state.text)
        assertEquals(4, state.lines.size)
        assertEquals("", state.lines[1].text) // Pre-existing empty line preserved
    }

    // ==================== EditorState.selectAll ====================

    @Test
    fun `selectAll selects entire text`() {
        val state = EditorState()
        state.updateFromText("hello world")
        state.selectAll()
        assertTrue(state.hasSelection)
        assertEquals(0, state.selection.start)
        assertEquals(11, state.selection.end)
    }

    @Test
    fun `selectAll on multiline text`() {
        val state = EditorState()
        state.updateFromText("line1\nline2\nline3")
        state.selectAll()
        assertEquals(0, state.selection.min)
        assertEquals(17, state.selection.max)
        assertEquals("line1\nline2\nline3", state.getSelectedText())
    }

    @Test
    fun `selectAll on empty text does nothing`() {
        val state = EditorState()
        state.selectAll()
        assertFalse(state.hasSelection)
    }

    // ==================== EditorState.clearSelection ====================

    @Test
    fun `clearSelection removes selection`() {
        val state = EditorState()
        state.updateFromText("hello world")
        state.setSelection(0, 5)
        assertTrue(state.hasSelection)
        state.clearSelection()
        assertFalse(state.hasSelection)
    }

    // ==================== EditorState indent/unindent/toggle operations ====================

    @Test
    fun `indent adds tab to current line`() {
        val state = EditorState()
        state.updateFromText("hello")
        state.indentInternal()
        assertEquals("\thello", state.text)
    }

    @Test
    fun `unindent removes tab from current line`() {
        val state = EditorState()
        state.updateFromText("\thello")
        state.unindentInternal()
        assertEquals("hello", state.text)
    }

    @Test
    fun `toggleBullet adds bullet to current line`() {
        val state = EditorState()
        state.updateFromText("item")
        state.toggleBulletInternal()
        assertEquals("• item", state.text)
    }

    @Test
    fun `toggleCheckbox adds checkbox to current line`() {
        val state = EditorState()
        state.updateFromText("task")
        state.toggleCheckboxInternal()
        assertEquals("☐ task", state.text)
    }

    // ==================== EditorState.handleSpaceWithSelection ====================

    @Test
    fun `handleSpaceWithSelection returns false when no selection`() {
        val state = EditorState()
        state.updateFromText("hello world")
        val result = state.handleSpaceWithSelectionInternal()
        assertFalse(result)
        assertEquals("hello world", state.text) // Text unchanged
    }

    @Test
    fun `handleSpaceWithSelection indents selected lines`() {
        val state = EditorState()
        state.updateFromText("line1\nline2\nline3")
        // Select "line2" (positions 6-11)
        state.setSelection(6, 11)
        val result = state.handleSpaceWithSelectionInternal()
        assertTrue(result)
        // line2 should be indented
        assertEquals("line1\n\tline2\nline3", state.text)
    }

    @Test
    fun `handleSpaceWithSelection indents multiple selected lines`() {
        val state = EditorState()
        state.updateFromText("line1\nline2\nline3\nline4")
        // Select across line2 and line3 (positions 6-17)
        state.setSelection(6, 17)
        val result = state.handleSpaceWithSelectionInternal()
        assertTrue(result)
        // Both lines should be indented
        assertEquals("line1\n\tline2\n\tline3\nline4", state.text)
    }

    @Test
    fun `handleSpaceWithSelection preserves selection after indent`() {
        val state = EditorState()
        state.updateFromText("line1\nline2\nline3")
        // Select "line2" (positions 6-11)
        state.setSelection(6, 11)
        state.handleSpaceWithSelectionInternal()
        // Selection should still exist and be adjusted
        assertTrue(state.hasSelection)
    }

    // ==================== EditorState indent/unindent with hidden lines ====================

    @Test
    fun `indentInternal skips hidden lines in selection`() {
        val state = EditorState()
        state.updateFromText("a\n\u2611 done\nb")
        // Select all text
        state.setSelection(0, state.text.length)
        state.indentInternal(hiddenIndices = setOf(1))
        assertEquals("\ta", state.lines[0].text)
        assertEquals("\u2611 done", state.lines[1].text) // unchanged
        assertEquals("\tb", state.lines[2].text)
    }

    @Test
    fun `unindentInternal skips hidden lines in selection`() {
        val state = EditorState()
        state.updateFromText("\ta\n\u2611 done\n\tb")
        // Select all text
        state.setSelection(0, state.text.length)
        state.unindentInternal(hiddenIndices = setOf(1))
        assertEquals("a", state.lines[0].text)
        assertEquals("\u2611 done", state.lines[1].text) // unchanged
        assertEquals("b", state.lines[2].text)
    }

    @Test
    fun `handleSpaceWithSelection double space unindents`() {
        val state = EditorState()
        state.updateFromText("line1\n\tline2\nline3")
        // Select just line2 content: "\tline2" is at positions 6-12
        // (line1=0-4, \n=5, \t=6, line2=7-11, \n=12)
        state.setSelection(6, 12)

        // First space - indents
        state.handleSpaceWithSelectionInternal()
        assertEquals("line1\n\t\tline2\nline3", state.text)

        // Second space within threshold - unindents twice (undo + go further)
        state.handleSpaceWithSelectionInternal()
        assertEquals("line1\nline2\nline3", state.text)
    }

    // ==================== toggleBullet/toggleCheckbox with selection ====================

    @Test
    fun `toggleBulletInternal applies to all selected lines`() {
        val state = EditorState()
        state.updateFromText("abc\ndef\nghi")
        state.setSelection(0, state.text.length)
        state.toggleBulletInternal()
        assertEquals("• abc", state.lines[0].text)
        assertEquals("• def", state.lines[1].text)
        assertEquals("• ghi", state.lines[2].text)
    }

    @Test
    fun `toggleCheckboxInternal applies to all selected lines`() {
        val state = EditorState()
        state.updateFromText("abc\ndef\nghi")
        state.setSelection(0, state.text.length)
        state.toggleCheckboxInternal()
        assertEquals("☐ abc", state.lines[0].text)
        assertEquals("☐ def", state.lines[1].text)
        assertEquals("☐ ghi", state.lines[2].text)
    }

    @Test
    fun `toggleBulletInternal preserves selection across multiple lines`() {
        val state = EditorState()
        state.updateFromText("abc\ndef")
        state.setSelection(0, 7)
        state.toggleBulletInternal()
        assertTrue(state.hasSelection)
        // Both lines should have bullets
        assertEquals("• abc", state.lines[0].text)
        assertEquals("• def", state.lines[1].text)
    }

    // ==================== LineState.moveCursor ====================

    @Test
    fun `moveCursor sets cursor to valid position`() {
        val line = LineState("hello", 0)
        line.moveCursor(3)
        assertEquals(3, line.cursorPosition)
    }

    @Test
    fun `moveCursor clamps negative position to zero`() {
        val line = LineState("hello", 3)
        line.moveCursor(-5)
        assertEquals(0, line.cursorPosition)
    }

    @Test
    fun `moveCursor clamps position beyond text length`() {
        val line = LineState("hi", 0)
        line.moveCursor(100)
        assertEquals(2, line.cursorPosition)
    }

    @Test
    fun `moveCursor does not modify text or noteIdContentLengths`() {
        val line = LineState("hello", 0, listOf("note1"))
        line.noteIdContentLengths = listOf(5)
        line.moveCursor(3)
        assertEquals("hello", line.text)
        assertEquals(listOf(5), line.noteIdContentLengths)
        assertEquals(listOf("note1"), line.noteIds)
    }

    // ==================== initFromNoteLines with preserveCursor ====================

    @Test
    fun `initFromNoteLines without preserveCursor resets to line 0`() {
        val state = EditorState()
        state.initFromNoteLines(listOf(
            "Line A" to listOf("a"),
            "Line B" to listOf("b"),
        ))
        state.lines[1].moveCursor(3)
        // Reinitialize without preserveCursor — focusedLineIndex should reset
        state.initFromNoteLines(listOf(
            "Line A updated" to listOf("a"),
            "Line B updated" to listOf("b"),
        ), preserveCursor = false)
        assertEquals(0, state.focusedLineIndex)
    }

    @Test
    fun `initFromNoteLines preserveCursor restores to same noteId line`() {
        val state = EditorState()
        state.initFromNoteLines(listOf(
            "Line A" to listOf("a"),
            "Line B" to listOf("b"),
            "Line C" to listOf("c"),
        ))
        // Focus line B at position 3
        state.focusedLineIndex = 1
        state.lines[1].moveCursor(3)

        // Reinitialize — line B still exists with same noteId
        state.initFromNoteLines(listOf(
            "Line A updated" to listOf("a"),
            "Line B updated" to listOf("b"),
            "Line C updated" to listOf("c"),
        ), preserveCursor = true)

        assertEquals(1, state.focusedLineIndex)
        assertEquals(3, state.lines[1].cursorPosition)
    }

    @Test
    fun `initFromNoteLines preserveCursor clamps position when line is shorter`() {
        val state = EditorState()
        state.initFromNoteLines(listOf(
            "Short" to listOf("a"),
            "A longer line here" to listOf("b"),
        ))
        state.focusedLineIndex = 1
        state.lines[1].moveCursor(15)

        // Line B becomes shorter
        state.initFromNoteLines(listOf(
            "Short" to listOf("a"),
            "Tiny" to listOf("b"),
        ), preserveCursor = true)

        assertEquals(1, state.focusedLineIndex)
        assertEquals(4, state.lines[1].cursorPosition) // clamped to "Tiny".length
    }

    @Test
    fun `initFromNoteLines preserveCursor falls back when noteId disappears`() {
        val state = EditorState()
        state.initFromNoteLines(listOf(
            "Line A" to listOf("a"),
            "Line B" to listOf("b"),
            "Line C" to listOf("c"),
        ))
        state.focusedLineIndex = 2 // focus on Line C

        // Line C removed
        state.initFromNoteLines(listOf(
            "Line A" to listOf("a"),
            "Line B" to listOf("b"),
        ), preserveCursor = true)

        // Should fall back to last line (index 1, clamped from 2)
        assertEquals(1, state.focusedLineIndex)
    }

    @Test
    fun `initFromNoteLines preserveCursor follows noteId when line moves`() {
        val state = EditorState()
        state.initFromNoteLines(listOf(
            "Line A" to listOf("a"),
            "Line B" to listOf("b"),
            "Line C" to listOf("c"),
        ))
        state.focusedLineIndex = 0 // focus on Line A

        // New line inserted before A — A moves to index 1
        state.initFromNoteLines(listOf(
            "New line" to listOf("new"),
            "Line A" to listOf("a"),
            "Line B" to listOf("b"),
            "Line C" to listOf("c"),
        ), preserveCursor = true)

        assertEquals(1, state.focusedLineIndex) // followed noteId "a" to index 1
    }

    @Test
    fun `initFromNoteLines preserveCursor with no previous noteId resets to 0`() {
        val state = EditorState()
        // Lines without noteIds
        state.initFromNoteLines(listOf(
            "Line A" to emptyList(),
            "Line B" to emptyList(),
        ))
        state.focusedLineIndex = 1

        state.initFromNoteLines(listOf(
            "Updated A" to listOf("a"),
            "Updated B" to listOf("b"),
        ), preserveCursor = true)

        // No previous noteId to match — falls back to 0
        assertEquals(0, state.focusedLineIndex)
    }

}
