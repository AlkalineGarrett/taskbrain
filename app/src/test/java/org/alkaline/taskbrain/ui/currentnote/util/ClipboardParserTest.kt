package org.alkaline.taskbrain.ui.currentnote.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipboardParserTest {

    // ==================== parseInternal ====================

    @Test
    fun `parseInternal parses plain text`() {
        assertEquals(
            listOf(ParsedLine(0, "", "hello")),
            ClipboardParser.parseInternal("hello")
        )
    }

    @Test
    fun `parseInternal parses bullet prefix`() {
        assertEquals(
            listOf(ParsedLine(0, "• ", "item")),
            ClipboardParser.parseInternal("• item")
        )
    }

    @Test
    fun `parseInternal parses unchecked checkbox prefix`() {
        assertEquals(
            listOf(ParsedLine(0, "☐ ", "todo")),
            ClipboardParser.parseInternal("☐ todo")
        )
    }

    @Test
    fun `parseInternal parses checked checkbox prefix`() {
        assertEquals(
            listOf(ParsedLine(0, "☑ ", "done")),
            ClipboardParser.parseInternal("☑ done")
        )
    }

    @Test
    fun `parseInternal parses indentation`() {
        assertEquals(
            listOf(ParsedLine(2, "• ", "nested")),
            ClipboardParser.parseInternal("\t\t• nested")
        )
    }

    @Test
    fun `parseInternal parses multiple lines`() {
        assertEquals(
            listOf(
                ParsedLine(0, "• ", "one"),
                ParsedLine(0, "• ", "two"),
                ParsedLine(1, "☐ ", "three"),
            ),
            ClipboardParser.parseInternal("• one\n• two\n\t☐ three")
        )
    }

    @Test
    fun `parseInternal parses line with only indent and no bullet`() {
        assertEquals(
            listOf(ParsedLine(1, "", "content")),
            ClipboardParser.parseInternal("\tcontent")
        )
    }

    // ==================== parseMarkdown ====================

    @Test
    fun `parseMarkdown parses dash bullets`() {
        assertEquals(
            listOf(
                ParsedLine(0, "• ", "item one"),
                ParsedLine(0, "• ", "item two"),
            ),
            ClipboardParser.parseMarkdown("- item one\n- item two")
        )
    }

    @Test
    fun `parseMarkdown parses asterisk bullets`() {
        assertEquals(
            listOf(ParsedLine(0, "• ", "item")),
            ClipboardParser.parseMarkdown("* item")
        )
    }

    @Test
    fun `parseMarkdown parses unchecked checkbox`() {
        assertEquals(
            listOf(ParsedLine(0, "☐ ", "todo")),
            ClipboardParser.parseMarkdown("- [ ] todo")
        )
    }

    @Test
    fun `parseMarkdown parses checked checkbox lowercase x`() {
        assertEquals(
            listOf(ParsedLine(0, "☑ ", "done")),
            ClipboardParser.parseMarkdown("- [x] done")
        )
    }

    @Test
    fun `parseMarkdown parses checked checkbox uppercase X`() {
        assertEquals(
            listOf(ParsedLine(0, "☑ ", "done")),
            ClipboardParser.parseMarkdown("- [X] done")
        )
    }

    @Test
    fun `parseMarkdown parses indentation from spaces`() {
        assertEquals(
            listOf(
                ParsedLine(1, "• ", "nested"),
                ParsedLine(2, "• ", "deeper"),
            ),
            ClipboardParser.parseMarkdown("  - nested\n    - deeper")
        )
    }

    @Test
    fun `parseMarkdown strips numbered list prefix`() {
        assertEquals(
            listOf(
                ParsedLine(0, "", "first"),
                ParsedLine(0, "", "second"),
            ),
            ClipboardParser.parseMarkdown("1. first\n2. second")
        )
    }

    // ==================== parseHtml ====================

    @Test
    fun `parseHtml parses simple unordered list`() {
        val html = "<ul><li>one</li><li>two</li></ul>"
        assertEquals(
            listOf(
                ParsedLine(0, "• ", "one"),
                ParsedLine(0, "• ", "two"),
            ),
            ClipboardParser.parseHtml(html)
        )
    }

    @Test
    fun `parseHtml parses nested lists with indentation`() {
        // The parser strips nested <ul>...</ul> from parent <li> content,
        // but the nested <li> text ("child") remains in the parent's text span
        // before the nested list tags, so parent gets "parentchild".
        val html = "<ul><li>parent<ul><li>child</li></ul></li></ul>"
        val result = ClipboardParser.parseHtml(html)!!
        assertEquals(2, result.size)
        // Parent line at indent 0
        assertEquals(0, result[0].indent)
        assertEquals("• ", result[0].bullet)
        // Child line at indent 1
        assertEquals(1, result[1].indent)
        assertEquals("• ", result[1].bullet)
        assertEquals("child", result[1].content)
    }

    @Test
    fun `parseHtml parses ordered list with no bullet`() {
        val html = "<ol><li>first</li><li>second</li></ol>"
        assertEquals(
            listOf(
                ParsedLine(0, "", "first"),
                ParsedLine(0, "", "second"),
            ),
            ClipboardParser.parseHtml(html)
        )
    }

    @Test
    fun `parseHtml returns null for non-list HTML`() {
        assertNull(ClipboardParser.parseHtml("<p>just text</p>"))
    }

    @Test
    fun `parseHtml parses checkbox inputs`() {
        val html = """<ul><li><input type="checkbox">todo</li><li><input type="checkbox" checked>done</li></ul>"""
        assertEquals(
            listOf(
                ParsedLine(0, "☐ ", "todo"),
                ParsedLine(0, "☑ ", "done"),
            ),
            ClipboardParser.parseHtml(html)
        )
    }

    // ==================== parse (top-level dispatcher) ====================

    @Test
    fun `parse uses HTML parsing when HTML has lists`() {
        val html = "<ul><li>one</li></ul>"
        val result = ClipboardParser.parse("one", html)
        assertEquals("• ", result[0].bullet)
    }

    @Test
    fun `parse falls back to markdown when text has markers`() {
        assertEquals(
            listOf(
                ParsedLine(0, "• ", "item one"),
                ParsedLine(0, "• ", "item two"),
            ),
            ClipboardParser.parse("- item one\n- item two", null)
        )
    }

    @Test
    fun `parse falls back to internal format for plain text`() {
        assertEquals(
            listOf(ParsedLine(0, "", "hello world")),
            ClipboardParser.parse("hello world", null)
        )
    }

    @Test
    fun `parse falls back to internal format for internal prefix text`() {
        assertEquals(
            listOf(
                ParsedLine(0, "☐ ", "one"),
                ParsedLine(0, "☐ ", "two"),
            ),
            ClipboardParser.parse("☐ one\n☐ two", null)
        )
    }

    @Test
    fun `parse normalizes CRLF line endings`() {
        val result = ClipboardParser.parse("a\r\nb\r\nc", null)
        assertEquals(3, result.size)
        assertEquals("a", result[0].content)
        assertEquals("b", result[1].content)
        assertEquals("c", result[2].content)
    }

    @Test
    fun `parse normalizes bare CR line endings`() {
        val result = ClipboardParser.parse("a\rb\rc", null)
        assertEquals(3, result.size)
        assertEquals("a", result[0].content)
        assertEquals("b", result[1].content)
        assertEquals("c", result[2].content)
    }

    @Test
    fun `parse ignores HTML without lists`() {
        assertEquals(
            listOf(ParsedLine(0, "", "plain text")),
            ClipboardParser.parse("plain text", "<p>plain text</p>")
        )
    }
}
