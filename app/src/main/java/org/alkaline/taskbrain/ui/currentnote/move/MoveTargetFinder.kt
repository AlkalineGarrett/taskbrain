package org.alkaline.taskbrain.ui.currentnote.move

import org.alkaline.taskbrain.ui.currentnote.LineState
import org.alkaline.taskbrain.ui.currentnote.selection.EditorSelection
import org.alkaline.taskbrain.ui.currentnote.selection.SelectionCoordinates
import org.alkaline.taskbrain.ui.currentnote.util.IndentationUtils

/**
 * Utility functions for finding move targets when reordering lines.
 * These are pure functions that determine where lines can be moved to.
 */
object MoveTargetFinder {

    /**
     * Finds the target position for moving a range up.
     * Returns null if at document boundary.
     */
    fun findMoveUpTarget(
        lines: List<LineState>,
        lineRange: IntRange,
        hiddenIndices: Set<Int> = emptySet()
    ): Int? {
        if (lineRange.first <= 0) return null
        val firstIndent = IndentationUtils.getIndentLevel(lines, lineRange.first)
        var target = lineRange.first - 1

        // Skip hidden lines and deeper lines (children of a previous group)
        while (target > 0 && (hiddenIndices.contains(target) || IndentationUtils.getIndentLevel(lines, target) > firstIndent)) {
            target--
        }
        // If target itself is hidden, no valid move
        if (hiddenIndices.contains(target)) return null

        // Also skip past any contiguous hidden block immediately above the target.
        // Completed items are sorted to the bottom of their section, so they sit
        // between the visible lines and the section separator. The hidden block
        // and the separator should be treated as one unit for move purposes.
        // Only skip if the block's shallowest indent <= the target's indent
        // (don't jump across indent boundaries).
        val targetIndent = IndentationUtils.getIndentLevel(lines, target)
        var probe = target
        var shallowest = Int.MAX_VALUE
        while (probe > 0 && hiddenIndices.contains(probe - 1)) {
            probe--
            shallowest = minOf(shallowest, IndentationUtils.getIndentLevel(lines, probe))
        }
        if (shallowest <= targetIndent) {
            target = probe
        }

        return target
    }

    /**
     * Finds the target position for moving a range down.
     * Returns null if at document boundary.
     */
    fun findMoveDownTarget(
        lines: List<LineState>,
        lineRange: IntRange,
        hiddenIndices: Set<Int> = emptySet()
    ): Int? {
        // Find first non-hidden line after the range
        var target = lineRange.last + 1
        while (target <= lines.lastIndex && hiddenIndices.contains(target)) {
            target++
        }
        if (target > lines.lastIndex) return null

        val firstIndent = IndentationUtils.getIndentLevel(lines, lineRange.first)
        val targetIndent = IndentationUtils.getIndentLevel(lines, target)

        // Find end of target's logical block (children only — deeper indent)
        if (targetIndent <= firstIndent) {
            while (target < lines.lastIndex) {
                val next = target + 1
                if (IndentationUtils.getIndentLevel(lines, next) > targetIndent) {
                    target++
                } else {
                    break
                }
            }
        }
        // Target position is after the block
        return target + 1
    }

    /**
     * Gets the move target for the current state.
     * Handles both selection and no-selection cases, including the special case
     * where selected lines span different indent levels.
     */
    fun getMoveTarget(
        lines: List<LineState>,
        hasSelection: Boolean,
        selection: EditorSelection,
        focusedLineIndex: Int,
        moveUp: Boolean,
        hiddenIndices: Set<Int> = emptySet()
    ): Int? {
        val range = if (hasSelection) {
            SelectionCoordinates.getSelectedLineRange(lines, selection, focusedLineIndex)
        } else {
            IndentationUtils.getLogicalBlock(lines, focusedLineIndex)
        }

        if (hasSelection) {
            // Check if first selected line is the shallowest
            val shallowest = range.minOfOrNull { IndentationUtils.getIndentLevel(lines, it) } ?: 0
            val firstIndent = IndentationUtils.getIndentLevel(lines, range.first)
            if (firstIndent > shallowest) {
                // First line isn't shallowest - move one line only
                return if (moveUp) {
                    if (range.first > 0) range.first - 1 else null
                } else {
                    if (range.last < lines.lastIndex) range.last + 2 else null
                }
            }
        }
        return if (moveUp) findMoveUpTarget(lines, range, hiddenIndices) else findMoveDownTarget(lines, range, hiddenIndices)
    }

    /**
     * Checks if moving with the current selection would break parent-child relationships.
     * Returns true when:
     * 1. Selection excludes children of the first selected line (orphaning children below)
     * 2. First selected line isn't the shallowest in selection (selecting children without parent)
     */
    fun wouldOrphanChildren(
        lines: List<LineState>,
        hasSelection: Boolean,
        selection: EditorSelection,
        focusedLineIndex: Int
    ): Boolean {
        if (!hasSelection) return false
        val selectedRange = SelectionCoordinates.getSelectedLineRange(lines, selection, focusedLineIndex)

        // Check if first line isn't the shallowest (selecting children without their context)
        val shallowest = selectedRange.minOfOrNull { IndentationUtils.getIndentLevel(lines, it) } ?: 0
        val firstIndent = IndentationUtils.getIndentLevel(lines, selectedRange.first)
        if (firstIndent > shallowest) {
            return true
        }

        // Check if selection excludes children of the first line
        val logicalBlock = IndentationUtils.getLogicalBlock(lines, selectedRange.first)
        return selectedRange.last < logicalBlock.last
    }
}
