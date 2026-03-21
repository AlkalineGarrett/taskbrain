package org.alkaline.taskbrain.ui.currentnote.util

import org.alkaline.taskbrain.ui.currentnote.LineState

/**
 * A parsed line from clipboard content, decomposed into structural parts.
 */
data class ParsedLine(
    val indent: Int,       // number of tab levels
    val bullet: String,    // "" | "• " | "☐ " | "☑ "
    val content: String,   // text after prefix
) {
    val hasPrefix: Boolean get() = indent > 0 || bullet.isNotEmpty()

    fun toLineText(): String = "\t".repeat(indent) + bullet + content
}

/**
 * Parses clipboard content into structured lines.
 *
 * Detects three formats (in priority order):
 * 1. HTML with list elements (from Word, Google Docs, etc.)
 * 2. Markdown list markers (-, *, - [ ], - [x], numbered)
 * 3. Internal format (tabs + bullet/checkbox characters)
 */
object ClipboardParser {

    fun parse(plainText: String, html: String?): List<ParsedLine> {
        // Normalize line endings (CRLF → LF, CR → LF)
        val normalized = plainText.replace("\r\n", "\n").replace("\r", "\n")
        if (html != null) {
            val htmlLines = parseHtml(html)
            if (htmlLines != null) return htmlLines
        }
        if (looksLikeMarkdown(normalized)) {
            return parseMarkdown(normalized)
        }
        return parseInternal(normalized)
    }

    // --- Internal format ---

    fun parseInternal(text: String): List<ParsedLine> =
        text.split('\n').map(::parseInternalLine)

    private fun parseInternalLine(line: String): ParsedLine {
        val prefix = LineState.extractPrefix(line)
        val indent = prefix.takeWhile { it == '\t' }.length
        val afterTabs = prefix.drop(indent)
        val bullet = LinePrefixes.LINE_PREFIXES.find { afterTabs == it } ?: ""
        val content = line.drop(prefix.length)
        return ParsedLine(indent, bullet, content)
    }

    // --- Markdown ---

    private val MD_CHECKBOX_CHECKED = Regex("""^(\s*)[-*]\s\[[xX]]\s""")
    private val MD_CHECKBOX_UNCHECKED = Regex("""^(\s*)[-*]\s\[\s]\s""")
    private val MD_BULLET = Regex("""^(\s*)[-*]\s""")
    private val MD_NUMBERED = Regex("""^(\s*)\d+\.\s""")

    private fun looksLikeMarkdown(text: String): Boolean =
        text.lineSequence().any { MD_BULLET.containsMatchIn(it) || MD_NUMBERED.containsMatchIn(it) }

    fun parseMarkdown(text: String): List<ParsedLine> =
        text.split('\n').map(::parseMarkdownLine)

    private fun parseMarkdownLine(line: String): ParsedLine {
        val leadingSpaces = line.takeWhile { it == ' ' || it == '\t' }
        val spaceCount = leadingSpaces.replace("\t", "    ").length
        val indent = spaceCount / 2

        // Try each pattern once (find returns null if no match)
        val (matchLen, bullet) = MD_CHECKBOX_CHECKED.find(line)?.let { it.value.length to LinePrefixes.CHECKBOX_CHECKED }
            ?: MD_CHECKBOX_UNCHECKED.find(line)?.let { it.value.length to LinePrefixes.CHECKBOX_UNCHECKED }
            ?: MD_BULLET.find(line)?.let { it.value.length to LinePrefixes.BULLET }
            ?: MD_NUMBERED.find(line)?.let { it.value.length to "" }
            ?: (0 to "")

        val content = if (matchLen > 0) line.drop(matchLen) else line.trimStart()
        return ParsedLine(indent, bullet, content)
    }

    // --- HTML ---

    fun parseHtml(html: String): List<ParsedLine>? {
        if (!html.contains("<li", ignoreCase = true)) return null
        return try {
            val lines = mutableListOf<ParsedLine>()
            parseHtmlTags(html, lines)
            lines.ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    // Pre-compiled regex patterns for HTML parsing
    private val HTML_TAG = Regex("""<(/?)([a-zA-Z]+)[^>]*>""", RegexOption.IGNORE_CASE)
    private val LI_END = Regex("</li>", RegexOption.IGNORE_CASE)
    private val NESTED_UL = Regex("<ul[^>]*>.*?</ul>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val NESTED_OL = Regex("<ol[^>]*>.*?</ol>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val CHECKED_INPUT = Regex("""<input[^>]*checked[^>]*>""", RegexOption.IGNORE_CASE)
    private val CHECKBOX_INPUT = Regex("""<input[^>]*type=["']checkbox["'][^>]*>""", RegexOption.IGNORE_CASE)

    /**
     * Simple tag-based HTML parser for list structures.
     * Tracks nesting depth for indentation.
     */
    private fun parseHtmlTags(html: String, lines: MutableList<ParsedLine>) {
        var pos = 0
        var indentLevel = 0
        var currentListType = "ul"  // default

        while (pos < html.length) {
            val tagMatch = HTML_TAG.find(html, pos) ?: break
            if (tagMatch.range.first > pos) {
                pos = tagMatch.range.first
                continue
            }

            val isClosing = tagMatch.groupValues[1] == "/"
            val tagName = tagMatch.groupValues[2].lowercase()

            when (tagName) {
                "ul", "ol" -> {
                    if (isClosing) {
                        indentLevel = maxOf(0, indentLevel - 1)
                    } else {
                        currentListType = tagName
                        indentLevel++
                    }
                }
                "li" -> {
                    if (!isClosing) {
                        val liStart = tagMatch.range.last + 1
                        val liEnd = LI_END.find(html, liStart)
                        if (liEnd != null) {
                            var content = html.substring(liStart, liEnd.range.first)
                            content = content.replace(NESTED_UL, "")
                            content = content.replace(NESTED_OL, "")

                            val hasCheckedBox = content.contains(CHECKED_INPUT)
                            val hasUncheckedBox = !hasCheckedBox && content.contains(CHECKBOX_INPUT)

                            content = HtmlEntities.stripTags(content)
                            content = HtmlEntities.unescape(content).trim()

                            if (content.isNotEmpty() || hasCheckedBox || hasUncheckedBox) {
                                val bullet = when {
                                    hasCheckedBox -> LinePrefixes.CHECKBOX_CHECKED
                                    hasUncheckedBox -> LinePrefixes.CHECKBOX_UNCHECKED
                                    currentListType == "ul" -> LinePrefixes.BULLET
                                    else -> ""
                                }
                                lines.add(ParsedLine(maxOf(0, indentLevel - 1), bullet, content))
                            }
                        }
                    }
                }
            }
            pos = tagMatch.range.last + 1
        }
    }
}
