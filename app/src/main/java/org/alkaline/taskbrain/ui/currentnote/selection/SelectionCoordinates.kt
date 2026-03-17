package org.alkaline.taskbrain.ui.currentnote.selection

import org.alkaline.taskbrain.ui.currentnote.LineState

/**
 * Utility functions for calculating selection coordinates and ranges.
 * These are pure functions that operate on line data without modifying state.
 */
object SelectionCoordinates {

    /**
     * Gets the character offset where a line starts in the full text.
     */
    fun getLineStartOffset(lines: List<LineState>, lineIndex: Int): Int {
        var offset = 0
        for (i in 0 until lineIndex.coerceIn(0, lines.size)) {
            offset += lines[i].text.length + 1 // +1 for newline
        }
        return offset
    }

    /**
     * Gets the line index and local offset for a global character offset.
     * Returns the last valid position if the offset is beyond the text length.
     */
    fun getLineAndLocalOffset(lines: List<LineState>, globalOffset: Int): Pair<Int, Int> {
        var remaining = globalOffset
        for (i in lines.indices) {
            val lineLength = lines[i].text.length
            if (remaining <= lineLength) {
                return i to remaining
            }
            remaining -= lineLength + 1 // +1 for newline
        }
        return (lines.lastIndex.coerceAtLeast(0)) to (lines.lastOrNull()?.text?.length ?: 0)
    }

    /**
     * Gets the selection range for a specific line (in local line offsets).
     * Returns null if the line has no selection.
     */
    fun getLineSelection(
        lines: List<LineState>,
        lineIndex: Int,
        selection: EditorSelection
    ): IntRange? {
        if (!selection.hasSelection || lineIndex !in lines.indices) {
            return null
        }

        val lineStart = getLineStartOffset(lines, lineIndex)
        val lineEnd = lineStart + lines[lineIndex].text.length

        val selMin = selection.min
        val selMax = selection.max

        if (selMax <= lineStart || selMin > lineEnd) {
            return null
        }

        val localStart = (selMin - lineStart).coerceIn(0, lines[lineIndex].text.length)
        val localEnd = (selMax - lineStart).coerceIn(0, lines[lineIndex].text.length)

        if (localStart == localEnd && lines[lineIndex].text.isNotEmpty()) {
            return null
        }

        return localStart until localEnd
    }

    /**
     * Gets the line indices covered by a selection.
     * Returns the focused line range if there's no selection.
     *
     * If the selection ends exactly at position 0 of a line (e.g., gutter selection
     * that includes the trailing newline), the previous line is used as the end.
     */
    fun getSelectedLineRange(
        lines: List<LineState>,
        selection: EditorSelection,
        focusedLineIndex: Int
    ): IntRange {
        if (!selection.hasSelection) {
            return focusedLineIndex..focusedLineIndex
        }
        val startLine = getLineAndLocalOffset(lines, selection.min).first
        val (endLine, endLocal) = getLineAndLocalOffset(lines, selection.max)

        // If selection ends at the very start of a line (offset 0),
        // consider the previous line as the actual end of the range
        val adjustedEndLine = if (endLocal == 0 && endLine > startLine) {
            endLine - 1
        } else {
            endLine
        }

        return startLine..adjustedEndLine
    }

    /**
     * Returns the effective selection range, possibly extended to include a trailing newline.
     *
     * When full lines are selected (selection starts at beginning of a line and ends at
     * end of a line), the trailing newline is included. This ensures that:
     * - Copying full lines includes the newline for proper pasting
     * - Deleting full lines removes the newline to avoid empty lines
     *
     * The extension only applies when the FIRST line is fully selected (selection starts
     * at line beginning). Partial selections starting mid-line are not extended.
     */
    fun getEffectiveSelectionRange(fullText: String, selection: EditorSelection): Pair<Int, Int> {
        val selStart = selection.min.coerceIn(0, fullText.length)
        val selEnd = selection.max.coerceIn(0, fullText.length)

        // Check if we should extend to include trailing newline
        val extendedEnd = if (shouldExtendSelectionToNewline(fullText, selStart, selEnd)) {
            selEnd + 1
        } else {
            selEnd
        }

        return selStart to extendedEnd
    }

    /**
     * Checks if a selection should be extended to include the trailing newline.
     *
     * Returns true when:
     * 1. Selection ends right before a newline (at end of line content)
     * 2. Selection starts at the beginning of a line (position 0 or right after a newline)
     */
    private fun shouldExtendSelectionToNewline(fullText: String, selStart: Int, selEnd: Int): Boolean {
        // Must end right before a newline
        if (selEnd >= fullText.length || fullText[selEnd] != '\n') return false

        // First line must be fully selected: selection starts at beginning of a line
        // This means either position 0, or the character before is a newline
        val startsAtLineBeginning = selStart == 0 || fullText[selStart - 1] == '\n'
        return startsAtLineBeginning
    }
}
