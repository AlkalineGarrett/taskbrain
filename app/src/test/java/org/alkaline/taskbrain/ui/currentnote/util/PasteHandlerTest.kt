package org.alkaline.taskbrain.ui.currentnote.util

import org.alkaline.taskbrain.ui.currentnote.LineState
import org.alkaline.taskbrain.ui.currentnote.selection.EditorSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasteHandlerTest {

    // --- Helpers ---

    private fun lines(vararg texts: String): List<LineState> =
        texts.map { LineState(it) }

    private fun lineTexts(ls: List<LineState>): List<String> =
        ls.map { it.text }

    private fun sel(start: Int, end: Int) = EditorSelection(start, end)

    private fun parsed(indent: Int, bullet: String, content: String) =
        ParsedLine(indent, bullet, content)

    private fun withCursor(ls: List<LineState>, lineIndex: Int, cursor: Int): List<LineState> {
        ls[lineIndex].updateFull(ls[lineIndex].text, cursor)
        return ls
    }

    // ==================== isFullLineSelection ====================

    @Test
    fun `isFullLineSelection detects full single line selection`() {
        val ls = lines("hello", "world")
        // "hello" is offsets 0-5
        assertTrue(PasteHandler.isFullLineSelection(ls, sel(0, 5)))
    }

    @Test
    fun `isFullLineSelection detects full multi-line selection`() {
        val ls = lines("hello", "world")
        // "hello\nworld" total = 11
        assertTrue(PasteHandler.isFullLineSelection(ls, sel(0, 11)))
    }

    @Test
    fun `isFullLineSelection rejects partial selection not starting at line beginning`() {
        val ls = lines("hello", "world")
        assertFalse(PasteHandler.isFullLineSelection(ls, sel(1, 5)))
    }

    @Test
    fun `isFullLineSelection rejects partial selection not ending at line end`() {
        val ls = lines("hello", "world")
        assertFalse(PasteHandler.isFullLineSelection(ls, sel(0, 3)))
    }

    @Test
    fun `isFullLineSelection detects selection ending at start of next line`() {
        val ls = lines("hello", "world")
        // offset 6 = start of "world" line
        assertTrue(PasteHandler.isFullLineSelection(ls, sel(0, 6)))
    }

    // ==================== Rule 5: single-line plain text paste ====================

    @Test
    fun `Rule 5 inserts at cursor`() {
        val ls = withCursor(lines("hello"), 0, 5)
        val result = PasteHandler.execute(ls, 0, EditorSelection.None, listOf(parsed(0, "", " world")))
        assertEquals(listOf("hello world"), lineTexts(result.lines))
    }

    @Test
    fun `Rule 5 replaces selection`() {
        val ls = lines("hello world")
        val result = PasteHandler.execute(ls, 0, sel(6, 11), listOf(parsed(0, "", "earth")))
        assertEquals(listOf("hello earth"), lineTexts(result.lines))
    }

    // ==================== Rule 3: full-line replacement ====================

    @Test
    fun `Rule 3 replaces selected lines with pasted lines`() {
        // "• hello" (0-7), "• world" (8-15)
        val ls = lines("• hello", "• world")
        val result = PasteHandler.execute(ls, 0, sel(0, 15), listOf(
            parsed(0, "☐ ", "one"),
            parsed(0, "☐ ", "two"),
        ))
        assertEquals(listOf("☐ one", "☐ two"), lineTexts(result.lines))
    }

    @Test
    fun `Rule 3 preserves lines before and after selection`() {
        val ls = lines("before", "• hello", "• world", "after")
        // "• hello" starts at offset 7, "• world" ends at offset 22
        val result = PasteHandler.execute(ls, 1, sel(7, 22), listOf(
            parsed(0, "☐ ", "replaced"),
        ))
        assertEquals(listOf("before", "☐ replaced", "after"), lineTexts(result.lines))
    }

    @Test
    fun `Rule 3 does not adopt destination prefix for unprefixed paste`() {
        val ls = lines("• hello", "• world")
        val result = PasteHandler.execute(ls, 0, sel(0, 15), listOf(
            parsed(0, "", "plain one"),
            parsed(0, "", "plain two"),
        ))
        assertEquals(listOf("plain one", "plain two"), lineTexts(result.lines))
    }

    // ==================== Rule 2: mid-line split for multi-line paste ====================

    @Test
    fun `Rule 2 splits at cursor and inserts pasted lines between halves`() {
        val ls = withCursor(lines("• hello"), 0, 4) // cursor after "he" in content: "• he|llo"
        val result = PasteHandler.execute(ls, 0, EditorSelection.None, listOf(
            parsed(0, "☐ ", "one"),
            parsed(0, "☐ ", "two"),
        ))
        assertEquals(listOf("• he", "☐ one", "☐ two", "• llo"), lineTexts(result.lines))
    }

    @Test
    fun `Rule 2 drops empty leading half when cursor is at start of content`() {
        val ls = withCursor(lines("• hello"), 0, 2) // cursor at "• |hello"
        val result = PasteHandler.execute(ls, 0, EditorSelection.None, listOf(
            parsed(0, "☐ ", "one"),
            parsed(0, "☐ ", "two"),
        ))
        assertEquals(listOf("☐ one", "☐ two", "• hello"), lineTexts(result.lines))
    }

    @Test
    fun `Rule 2 drops empty trailing half when cursor is at end of content`() {
        val ls = withCursor(lines("• hello"), 0, 7) // cursor at "• hello|"
        val result = PasteHandler.execute(ls, 0, EditorSelection.None, listOf(
            parsed(0, "☐ ", "one"),
            parsed(0, "☐ ", "two"),
        ))
        assertEquals(listOf("• hello", "☐ one", "☐ two"), lineTexts(result.lines))
    }

    @Test
    fun `Rule 2 handles partial single-line selection with split`() {
        val ls = lines("• hello")
        // Select "ll" in "• hello": global offsets 4-6
        val result = PasteHandler.execute(ls, 0, sel(4, 6), listOf(
            parsed(0, "☐ ", "one"),
            parsed(0, "☐ ", "two"),
        ))
        assertEquals(listOf("• he", "☐ one", "☐ two", "• o"), lineTexts(result.lines))
    }

    // ==================== Rule 1: prefix merging ====================

    @Test
    fun `Rule 1 source prefix wins when source has prefix`() {
        val ls = withCursor(lines("• hello"), 0, 7)
        val result = PasteHandler.execute(ls, 0, EditorSelection.None, listOf(
            parsed(0, "☐ ", "one"),
            parsed(0, "☐ ", "two"),
        ))
        assertEquals(listOf("• hello", "☐ one", "☐ two"), lineTexts(result.lines))
    }

    @Test
    fun `Rule 1 adopts destination prefix when source has no prefix`() {
        val ls = withCursor(lines("☐ hello"), 0, 7)
        val result = PasteHandler.execute(ls, 0, EditorSelection.None, listOf(
            parsed(0, "", "one"),
            parsed(0, "", "two"),
        ))
        assertEquals(listOf("☐ hello", "☐ one", "☐ two"), lineTexts(result.lines))
    }

    @Test
    fun `Rule 1 per-line prefix adoption with mixed source prefixes`() {
        val ls = withCursor(lines("☐ hello"), 0, 7)
        val result = PasteHandler.execute(ls, 0, EditorSelection.None, listOf(
            parsed(0, "• ", "bullet"),
            parsed(0, "", "plain"),
            parsed(0, "☑ ", "checked"),
        ))
        assertEquals(
            listOf("☐ hello", "• bullet", "☐ plain", "☑ checked"),
            lineTexts(result.lines)
        )
    }

    // ==================== Rule 4: relative indent shifting ====================

    @Test
    fun `Rule 4 shifts indentation to match destination`() {
        val ls = withCursor(lines("\t• hello"), 0, 8) // indent level 1
        val result = PasteHandler.execute(ls, 0, EditorSelection.None, listOf(
            parsed(2, "☐ ", "parent"),
            parsed(3, "☐ ", "child"),
            parsed(2, "☐ ", "sibling"),
        ))
        // delta = 1 - 2 = -1, so 2->1, 3->2, 2->1
        assertEquals(
            listOf("\t• hello", "\t☐ parent", "\t\t☐ child", "\t☐ sibling"),
            lineTexts(result.lines)
        )
    }

    @Test
    fun `Rule 4 clamps indent to zero`() {
        val ls = withCursor(lines("• hello"), 0, 7) // indent level 0
        val result = PasteHandler.execute(ls, 0, EditorSelection.None, listOf(
            parsed(1, "☐ ", "one"),
            parsed(0, "☐ ", "two"),
        ))
        // delta = 0 - 1 = -1, so 1->0, 0->0(clamped)
        assertEquals(listOf("• hello", "☐ one", "☐ two"), lineTexts(result.lines))
    }

    // ==================== Combined scenarios ====================

    @Test
    fun `multi-line paste onto prefix-only line with source prefix`() {
        val ls = withCursor(lines("• "), 0, 2)
        val result = PasteHandler.execute(ls, 0, EditorSelection.None, listOf(
            parsed(0, "☐ ", "one"),
            parsed(0, "☐ ", "two"),
        ))
        // Prefix-only line is replaced (no content to preserve)
        assertEquals(listOf("☐ one", "☐ two"), lineTexts(result.lines))
    }

    @Test
    fun `multi-line paste onto prefix-only line with no source prefix`() {
        val ls = withCursor(lines("• "), 0, 2)
        val result = PasteHandler.execute(ls, 0, EditorSelection.None, listOf(
            parsed(0, "", "one"),
            parsed(0, "", "two"),
        ))
        // Adopt destination bullet; prefix-only line is replaced
        assertEquals(listOf("• one", "• two"), lineTexts(result.lines))
    }

    @Test
    fun `external plain text paste onto checkbox line`() {
        val ls = withCursor(lines("☐ "), 0, 2)
        val result = PasteHandler.execute(ls, 0, EditorSelection.None, listOf(
            parsed(0, "", "one"),
            parsed(0, "", "two"),
            parsed(0, "", "three"),
        ))
        // Prefix-only checkbox line is replaced
        assertEquals(listOf("☐ one", "☐ two", "☐ three"), lineTexts(result.lines))
    }

    @Test
    fun `partial multi-line selection replacement`() {
        // "• hello" (0-7) \n "• world" (8-15)
        // Select "llo\n• wor" = offsets 4-13
        val ls = lines("• hello", "• world")
        val result = PasteHandler.execute(ls, 0, sel(4, 13), listOf(
            parsed(0, "☐ ", "one"),
            parsed(0, "☐ ", "two"),
        ))
        assertEquals(listOf("• he", "☐ one", "☐ two", "• ld"), lineTexts(result.lines))
    }

    @Test
    fun `paste onto empty line replaces it`() {
        val ls = withCursor(lines(""), 0, 0)
        val result = PasteHandler.execute(ls, 0, EditorSelection.None, listOf(
            parsed(0, "☐ ", "one"),
            parsed(0, "☐ ", "two"),
        ))
        // Empty line is replaced, not scooted down
        assertEquals(listOf("☐ one", "☐ two"), lineTexts(result.lines))
    }
}
