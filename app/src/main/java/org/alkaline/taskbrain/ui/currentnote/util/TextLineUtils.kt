package org.alkaline.taskbrain.ui.currentnote.util

/**
 * Data class representing the boundaries of a line in text.
 */
data class LineBounds(
    val start: Int,  // Start offset (inclusive)
    val end: Int     // End offset (exclusive, does not include the newline)
)

/**
 * Data class representing parsed information about a line.
 */
data class LineInfo(
    val indentation: String,  // Leading tabs
    val prefix: String?,      // Bullet or checkbox prefix, null if none
    val content: String,      // Content after indentation and prefix
    val fullLine: String      // The complete line
)

/**
 * Shared utilities for text line manipulation.
 */
object TextLineUtils {

    /**
     * Finds the start offset of the line containing the given position.
     */
    fun findLineStart(text: String, position: Int): Int {
        if (position <= 0) return 0
        val idx = text.lastIndexOf('\n', position - 1)
        return if (idx == -1) 0 else idx + 1
    }

    /**
     * Finds the end offset of the line containing the given position.
     * The end offset is exclusive and does not include the newline character.
     */
    fun findLineEnd(text: String, position: Int): Int {
        val idx = text.indexOf('\n', position)
        return if (idx == -1) text.length else idx
    }

    /**
     * Gets the line boundaries for the line containing the given position.
     */
    fun getLineBounds(text: String, position: Int): LineBounds {
        return LineBounds(
            start = findLineStart(text, position),
            end = findLineEnd(text, position)
        )
    }

    /**
     * Gets the line boundaries for a selection range.
     * Returns bounds covering all lines touched by the selection.
     */
    fun getSelectionLineBounds(text: String, selStart: Int, selEnd: Int): LineBounds {
        val firstLineStart = findLineStart(text, selStart)
        val lastLineEnd = if (selStart == selEnd) {
            findLineEnd(text, selStart)
        } else {
            // For multi-char selections, find the line containing selEnd-1
            // (selEnd could be at position 0 of next line)
            findLineEnd(text, (selEnd - 1).coerceAtLeast(selStart))
        }
        return LineBounds(firstLineStart, lastLineEnd)
    }

    /**
     * Extracts the indentation (leading tabs) from a line.
     */
    fun extractIndentation(line: String): String {
        return line.takeWhile { it == '\t' }
    }

    /**
     * Removes the indentation from a line.
     */
    fun removeIndentation(line: String): String {
        return line.dropWhile { it == '\t' }
    }

    /**
     * Parses a line into its components: indentation, prefix, and content.
     */
    fun parseLine(line: String): LineInfo {
        val indentation = extractIndentation(line)
        val afterIndent = line.removePrefix(indentation)
        val prefix = LinePrefixes.LINE_PREFIXES.find { afterIndent.startsWith(it) }
        val content = if (prefix != null) afterIndent.removePrefix(prefix) else afterIndent
        return LineInfo(indentation, prefix, content, line)
    }

    /**
     * Gets the line content at the specified position.
     */
    fun getLineContent(text: String, position: Int): String {
        val bounds = getLineBounds(text, position)
        return text.substring(bounds.start, bounds.end)
    }

    /**
     * Returns true if the position is at the start of a line.
     */
    fun isAtLineStart(text: String, position: Int): Boolean {
        return position == 0 || (position > 0 && text[position - 1] == '\n')
    }

    /**
     * Returns true if the position is at the end of a line.
     */
    fun isAtLineEnd(text: String, position: Int): Boolean {
        return position == text.length || text[position] == '\n'
    }

    /**
     * Returns true if the selection covers complete line(s).
     */
    fun isFullLineSelection(text: String, selStart: Int, selEnd: Int): Boolean {
        if (selStart == selEnd) return false
        return isAtLineStart(text, selStart) && isAtLineEnd(text, selEnd)
    }

    /**
     * Gets all lines within a selection range as a list of strings.
     */
    fun getLinesInSelection(text: String, selStart: Int, selEnd: Int): List<String> {
        val bounds = getSelectionLineBounds(text, selStart, selEnd)
        val selectedText = text.substring(bounds.start, bounds.end)
        return selectedText.split('\n')
    }

    /**
     * Processes each line in the selection with a transform function and returns the result.
     * Returns the transformed text, new selection range, and cursor adjustment for collapsed selections.
     */
    fun processLinesInSelection(
        text: String,
        selStart: Int,
        selEnd: Int,
        transform: (String) -> String
    ): ProcessedLines {
        val bounds = getSelectionLineBounds(text, selStart, selEnd)
        val beforeSelection = text.substring(0, bounds.start)
        val selectedLines = text.substring(bounds.start, bounds.end)
        val afterSelection = text.substring(bounds.end)

        val processedLines = selectedLines.split('\n')
            .map(transform)
            .joinToString("\n")

        val newText = beforeSelection + processedLines + afterSelection
        return ProcessedLines(
            text = newText,
            newSelStart = bounds.start,
            newSelEnd = bounds.start + processedLines.length,
            lengthDelta = processedLines.length - selectedLines.length
        )
    }

    /**
     * Returns the line index (0-based) for a given character offset.
     */
    fun getLineIndex(text: String, offset: Int): Int {
        if (offset <= 0) return 0
        var lineIndex = 0
        var pos = 0
        while (pos < offset && pos < text.length) {
            if (text[pos] == '\n') lineIndex++
            pos++
        }
        return lineIndex
    }

    /**
     * Returns the character offset for the start of a given line index.
     */
    fun getLineStartOffset(text: String, lineIndex: Int): Int {
        if (lineIndex <= 0) return 0
        var currentLine = 0
        var pos = 0
        while (pos < text.length && currentLine < lineIndex) {
            if (text[pos] == '\n') currentLine++
            pos++
        }
        return pos
    }

    /**
     * Trims a line for use in alarm display:
     * 1. Removes leading tabs, bullet/checkbox prefix, and whitespace
     * 2. Removes trailing alarm symbol (⏰) and any space before it
     */
    fun trimLineForAlarm(line: String): String {
        // Parse line to get content without indentation and prefix
        val parsed = parseLine(line)
        var result = parsed.content

        // Trim leading whitespace
        result = result.trimStart()

        // Remove alarm directives and plain alarm symbols
        result = AlarmSymbolUtils.stripAlarmMarkers(result)

        // Trim any remaining whitespace
        result = result.trim()

        return result
    }
}

/**
 * Result of processing lines with a transform function.
 */
data class ProcessedLines(
    val text: String,
    val newSelStart: Int,
    val newSelEnd: Int,
    val lengthDelta: Int
)
