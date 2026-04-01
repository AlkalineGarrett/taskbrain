package org.alkaline.taskbrain.ui.currentnote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import org.alkaline.taskbrain.ui.currentnote.move.MoveExecutor
import org.alkaline.taskbrain.ui.currentnote.move.MoveTargetFinder
import org.alkaline.taskbrain.ui.currentnote.selection.EditorSelection
import org.alkaline.taskbrain.ui.currentnote.selection.SelectionCoordinates
import org.alkaline.taskbrain.ui.currentnote.util.IndentationUtils

/**
 * State holder for the entire editor.
 */
class EditorState {
    internal val lines: SnapshotStateList<LineState> = mutableStateListOf(LineState(""))
    var focusedLineIndex by mutableIntStateOf(0)
        internal set

    var selection by mutableStateOf(EditorSelection.None)
        internal set

    internal var onTextChange: ((String) -> Unit)? = null

    /** Timestamp of the last space-triggered indent (for double-space → unindent) */
    private var lastSpaceIndentTime: Long = 0L

    companion object {
        /** Maximum time between space presses for double-space unindent (in milliseconds) */
        const val DOUBLE_SPACE_THRESHOLD_MS = 250L
    }

    /**
     * Version counter that increments on any state change requiring focus update.
     * Observed by the editor composable to trigger focus requests.
     */
    var stateVersion by mutableIntStateOf(0)
        private set

    /**
     * Increments state version to signal that focus should be updated.
     */
    internal fun requestFocusUpdate() {
        stateVersion++
    }

    val text: String get() = lines.joinToString("\n") { it.text }

    val currentLine: LineState? get() = lines.getOrNull(focusedLineIndex)

    val hasSelection: Boolean get() = selection.hasSelection

    /**
     * Gets the character offset where a line starts in the full text.
     */
    fun getLineStartOffset(lineIndex: Int): Int =
        SelectionCoordinates.getLineStartOffset(lines, lineIndex)

    /**
     * Gets the line index and local offset for a global character offset.
     */
    fun getLineAndLocalOffset(globalOffset: Int): Pair<Int, Int> =
        SelectionCoordinates.getLineAndLocalOffset(lines, globalOffset)

    /**
     * Gets the selection range for a specific line (in local line offsets).
     * Returns null if the line has no selection.
     */
    fun getLineSelection(lineIndex: Int): IntRange? =
        SelectionCoordinates.getLineSelection(lines, lineIndex, selection)

    fun setSelection(start: Int, end: Int) {
        selection = EditorSelection(start, end)

        // Move cursor to the start of the selection (invisibly, since cursor is hidden during selection)
        // This ensures that when selection is cleared, cursor is already at the right position
        val cursorPos = selection.min
        val (lineIndex, localOffset) = getLineAndLocalOffset(cursorPos)
        focusedLineIndex = lineIndex
        lines.getOrNull(lineIndex)?.updateFull(lines[lineIndex].text, localOffset)
    }

    fun clearSelection() {
        selection = EditorSelection.None
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
    private fun getEffectiveSelectionRange(fullText: String): Pair<Int, Int> =
        SelectionCoordinates.getEffectiveSelectionRange(fullText, selection)

    fun getSelectedText(): String {
        if (!hasSelection) return ""
        val fullText = text
        val (selStart, selEnd) = getEffectiveSelectionRange(fullText)
        return fullText.substring(selStart, selEnd)
    }

    /**
     * Internal: Use EditorController.deleteSelectionWithUndo() for proper undo handling.
     */
    internal fun deleteSelectionInternal(): Int {
        if (!hasSelection) return -1

        val fullText = text
        val (selStart, selEnd) = getEffectiveSelectionRange(fullText)
        val newText = fullText.substring(0, selStart) + fullText.substring(selEnd)
        val newCursorPos = selStart

        updateFromText(newText)
        clearSelection()

        val (lineIndex, localOffset) = getLineAndLocalOffset(newCursorPos)
        focusedLineIndex = lineIndex
        lines.getOrNull(lineIndex)?.updateFull(lines[lineIndex].text, localOffset)

        requestFocusUpdate()
        notifyChange()
        return newCursorPos
    }

    /**
     * Replaces the current selection with new text.
     * If no selection, inserts at the current cursor position.
     * Returns the new cursor position (at the end of inserted text).
     * Internal: Use EditorController.paste() for proper undo handling.
     */
    internal fun replaceSelectionInternal(replacement: String): Int {
        val fullText = text
        val insertPos: Int
        val newText: String

        if (hasSelection) {
            val (selStart, selEnd) = getEffectiveSelectionRange(fullText)
            insertPos = selStart
            newText = fullText.substring(0, selStart) + replacement + fullText.substring(selEnd)
        } else {
            // Insert at current cursor position
            val currentLine = lines.getOrNull(focusedLineIndex) ?: return 0
            insertPos = getLineStartOffset(focusedLineIndex) + currentLine.cursorPosition
            newText = fullText.substring(0, insertPos) + replacement + fullText.substring(insertPos)
        }

        val newCursorPos = insertPos + replacement.length

        updateFromText(newText)
        removeEmptyLineAtCursor(newCursorPos)
        clearSelection()

        val (lineIndex, localOffset) = getLineAndLocalOffset(newCursorPos)
        focusedLineIndex = lineIndex
        lines.getOrNull(lineIndex)?.updateFull(lines[lineIndex].text, localOffset)

        requestFocusUpdate()
        notifyChange()
        return newCursorPos
    }

    /**
     * Selects all text in the editor.
     */
    fun selectAll() {
        val fullText = text
        if (fullText.isNotEmpty()) {
            setSelection(0, fullText.length)
        }
    }

    /**
     * Handles space key when there's a selection.
     *
     * - Single space: indent all selected lines
     * - Double space (within 250ms): undo the indent and unindent instead
     *
     * Uses the same indent/unindent methods as the command buttons.
     * Called via EditorController which handles undo tracking.
     *
     * @return true if the space was handled (there was a selection), false otherwise
     */
    internal fun handleSpaceWithSelectionInternal(): Boolean {
        if (!hasSelection) return false

        val now = System.currentTimeMillis()
        val timeSinceLastIndent = now - lastSpaceIndentTime

        if (timeSinceLastIndent <= DOUBLE_SPACE_THRESHOLD_MS && lastSpaceIndentTime > 0) {
            // Double-space: undo the indent and unindent
            // First undo the indent we just did by unindenting twice
            unindentInternal()
            unindentInternal()
            lastSpaceIndentTime = 0L // Reset so triple-space doesn't re-trigger
        } else {
            // Single space: indent
            indentInternal()
            lastSpaceIndentTime = now
        }

        return true
    }

    internal fun indentInternal(hiddenIndices: Set<Int> = emptySet()) {
        val linesToIndent = getSelectedLineIndices(hiddenIndices)
        val hadSelection = hasSelection
        val oldSelStart = selection.start
        val oldSelEnd = selection.end

        linesToIndent.forEach { lineIndex ->
            lines.getOrNull(lineIndex)?.indent()
        }

        // Adjust selection to account for added tabs
        if (hadSelection) {
            val (startLine, _) = getLineAndLocalOffset(oldSelStart)
            val (endLine, _) = getLineAndLocalOffset(oldSelEnd)

            // Count tabs added before/at each selection endpoint
            val tabsBeforeStart = linesToIndent.count { it <= startLine }
            val tabsBeforeEnd = linesToIndent.count { it <= endLine }

            selection = EditorSelection(
                oldSelStart + tabsBeforeStart,
                oldSelEnd + tabsBeforeEnd
            )
        }

        requestFocusUpdate()
        notifyChange()
    }

    internal fun unindentInternal(hiddenIndices: Set<Int> = emptySet()) {
        val linesToUnindent = getSelectedLineIndices(hiddenIndices)
        val hadSelection = hasSelection
        val oldSelStart = selection.start
        val oldSelEnd = selection.end
        val (startLine, _) = if (hadSelection) getLineAndLocalOffset(oldSelStart) else (0 to 0)
        val (endLine, _) = if (hadSelection) getLineAndLocalOffset(oldSelEnd) else (0 to 0)

        // Track which lines actually unindented
        val unindentedLines = mutableListOf<Int>()
        linesToUnindent.forEach { lineIndex ->
            if (lines.getOrNull(lineIndex)?.unindent() == true) {
                unindentedLines.add(lineIndex)
            }
        }

        if (unindentedLines.isNotEmpty()) {
            // Adjust selection to account for removed tabs
            if (hadSelection) {
                val tabsRemovedBeforeStart = unindentedLines.count { it <= startLine }
                val tabsRemovedBeforeEnd = unindentedLines.count { it <= endLine }

                selection = EditorSelection(
                    (oldSelStart - tabsRemovedBeforeStart).coerceAtLeast(0),
                    (oldSelEnd - tabsRemovedBeforeEnd).coerceAtLeast(0)
                )
            }

            requestFocusUpdate()
            notifyChange()
        }
    }

    /**
     * Gets the indices of all lines that are part of the current selection.
     * If no selection, returns just the focused line index.
     */
    private fun getSelectedLineIndices(hiddenIndices: Set<Int> = emptySet()): List<Int> {
        if (!hasSelection) {
            return listOf(focusedLineIndex)
        }

        val startLine = getLineAndLocalOffset(selection.min).first
        val endLine = getLineAndLocalOffset(selection.max).first
        return (startLine..endLine).filter { it !in hiddenIndices }
    }

    internal fun toggleBulletInternal() {
        togglePrefixOnSelectedLines { it.toggleBullet() }
    }

    internal fun toggleCheckboxInternal() {
        togglePrefixOnSelectedLines { it.toggleCheckbox() }
    }

    private fun togglePrefixOnSelectedLines(toggle: (LineState) -> Unit) {
        val lineIndices = getSelectedLineIndices()
        val hadSelection = hasSelection
        val oldSelStart = selection.start
        val oldSelEnd = selection.end

        data class LineDelta(val lineIndex: Int, val delta: Int)
        val deltas = mutableListOf<LineDelta>()
        for (lineIndex in lineIndices) {
            val line = lines.getOrNull(lineIndex) ?: continue
            val lenBefore = line.text.length
            toggle(line)
            deltas.add(LineDelta(lineIndex, line.text.length - lenBefore))
        }

        if (hadSelection) {
            val (startLine, _) = getLineAndLocalOffset(oldSelStart)
            val (endLine, _) = getLineAndLocalOffset(oldSelEnd)
            var startAdjust = 0
            var endAdjust = 0
            for ((lineIndex, delta) in deltas) {
                if (lineIndex <= startLine) startAdjust += delta
                if (lineIndex <= endLine) endAdjust += delta
            }
            selection = EditorSelection(
                maxOf(0, oldSelStart + startAdjust),
                maxOf(0, oldSelEnd + endAdjust)
            )
        }

        requestFocusUpdate()
        notifyChange()
    }

    // =========================================================================
    // Move Lines Operations
    // =========================================================================

    /**
     * Gets the indentation level of a line (number of leading tabs).
     * Empty lines have indent level 0.
     */
    fun getIndentLevel(lineIndex: Int): Int =
        IndentationUtils.getIndentLevel(lines, lineIndex)

    /**
     * Gets the logical block for a line: the line itself plus all deeper-indented children below it.
     */
    fun getLogicalBlock(startIndex: Int): IntRange =
        IndentationUtils.getLogicalBlock(lines, startIndex)

    /**
     * Gets the line indices covered by the current selection.
     * Returns the focused line if there's no selection.
     *
     * If the selection ends exactly at position 0 of a line (e.g., gutter selection
     * that includes the trailing newline), the previous line is used as the end.
     */
    fun getSelectedLineRange(): IntRange =
        SelectionCoordinates.getSelectedLineRange(lines, selection, focusedLineIndex)

    /**
     * Finds the target position for moving a range up.
     * Returns null if at document boundary.
     */
    fun findMoveUpTarget(lineRange: IntRange): Int? =
        MoveTargetFinder.findMoveUpTarget(lines, lineRange)

    /**
     * Finds the target position for moving a range down.
     * Returns null if at document boundary.
     */
    fun findMoveDownTarget(lineRange: IntRange): Int? =
        MoveTargetFinder.findMoveDownTarget(lines, lineRange)

    /**
     * Gets the move target for the current state.
     * Handles both selection and no-selection cases, including the special case
     * where selected lines span different indent levels.
     */
    fun getMoveTarget(moveUp: Boolean, hiddenIndices: Set<Int> = emptySet()): Int? =
        MoveTargetFinder.getMoveTarget(lines, hasSelection, selection, focusedLineIndex, moveUp, hiddenIndices)

    /**
     * Checks if moving with the current selection would break parent-child relationships.
     * Returns true when:
     * 1. Selection excludes children of the first selected line (orphaning children below)
     * 2. First selected line isn't the shallowest in selection (selecting children without parent)
     */
    fun wouldOrphanChildren(): Boolean =
        MoveTargetFinder.wouldOrphanChildren(lines, hasSelection, selection, focusedLineIndex)

    /**
     * Moves lines from sourceRange to targetIndex.
     * Adjusts focused line and selection to follow the moved lines.
     * Returns the new range of the moved lines, or null if move failed.
     */
    internal fun moveLinesInternal(sourceRange: IntRange, targetIndex: Int): IntRange? {
        // Capture noteIds before the move
        val oldNoteIds = lines.map { it.noteIds }

        // Calculate the move result using pure function
        val selectionForCalc = if (hasSelection) selection else null
        val result = MoveExecutor.calculateMove(
            lines = lines,
            sourceRange = sourceRange,
            targetIndex = targetIndex,
            focusedLineIndex = focusedLineIndex,
            selection = selectionForCalc
        ) ?: return null

        // Calculate noteIds reordering (same logic as text reordering in MoveExecutor)
        val moveCount = sourceRange.last - sourceRange.first + 1
        val reorderedNoteIds = mutableListOf<List<String>>()
        for (i in oldNoteIds.indices) {
            if (i !in sourceRange) reorderedNoteIds.add(oldNoteIds[i])
        }
        val adjustedTarget = if (targetIndex > sourceRange.first) targetIndex - moveCount else targetIndex
        for (i in sourceRange) {
            reorderedNoteIds.add(adjustedTarget + (i - sourceRange.first), oldNoteIds[i])
        }

        // Apply the result to state
        lines.clear()
        result.newLines.forEachIndexed { index, lineText ->
            val noteIds = reorderedNoteIds.getOrElse(index) { emptyList() }
            lines.add(LineState(lineText, lineText.length, noteIds))
        }
        focusedLineIndex = result.newFocusedLineIndex
        if (result.newSelection != null) {
            selection = result.newSelection
        }

        requestFocusUpdate()
        notifyChange()
        return result.newRange
    }

    /**
     * Inserts text at the end of the current line.
     * Adds a space before the text if the line doesn't end with whitespace.
     * Internal: Use EditorController.insertAtEndOfCurrentLine() for proper undo handling.
     */
    internal fun insertAtEndOfCurrentLineInternal(textToInsert: String) {
        val line = currentLine ?: return
        val lineText = line.text

        // Add space before if line doesn't end with whitespace and isn't empty
        val prefix = if (lineText.isNotEmpty() && !lineText.last().isWhitespace()) " " else ""
        val newLineText = lineText + prefix + textToInsert

        line.updateFull(newLineText, line.cursorPosition)
        notifyChange()
    }

    internal fun updateFromText(newText: String) {
        val oldNoteIds = lines.map { it.noteIds }
        val oldContents = lines.map { it.text }
        val oldConsumed = BooleanArray(oldContents.size)
        val newLines = newText.split("\n")

        // Build content→indices map for exact matching
        val contentToIndices = mutableMapOf<String, MutableList<Int>>()
        oldContents.forEachIndexed { i, content ->
            contentToIndices.getOrPut(content) { mutableListOf() }.add(i)
        }

        val matchedNoteIds = arrayOfNulls<List<String>>(newLines.size)

        // Exact content match
        newLines.forEachIndexed { index, lineText ->
            val indices = contentToIndices[lineText]
            if (!indices.isNullOrEmpty()) {
                val oldIdx = indices.removeAt(0)
                matchedNoteIds[index] = oldNoteIds[oldIdx]
                oldConsumed[oldIdx] = true
            }
        }

        // Similarity-based matching for modifications and splits
        org.alkaline.taskbrain.data.performSimilarityMatching(
            unmatchedNewIndices = newLines.indices.filter { matchedNoteIds[it] == null }.toSet(),
            unconsumedOldIndices = oldContents.indices.filter { !oldConsumed[it] },
            getOldContent = { oldContents[it] },
            getNewContent = { newLines[it] },
        ) { oldIdx, newIdx ->
            matchedNoteIds[newIdx] = oldNoteIds[oldIdx]
            oldConsumed[oldIdx] = true
        }

        lines.clear()
        newLines.forEachIndexed { index, lineText ->
            lines.add(LineState(lineText, lineText.length, matchedNoteIds[index] ?: emptyList()))
        }
        focusedLineIndex = focusedLineIndex.coerceIn(0, lines.lastIndex.coerceAtLeast(0))
    }

    /**
     * Syncs primary noteIds from the line tracker into LineState.
     *
     * The line tracker (NoteLineTracker) is the source of truth for noteIds.
     * LineState also maintains noteIds for undo/redo snapshots and merge tracking.
     * This method keeps them in sync so directive key lookups use consistent noteIds.
     */
    internal fun syncNoteIds(trackerNoteIds: List<String?>) {
        for (i in lines.indices) {
            val trackerId = trackerNoteIds.getOrNull(i) ?: continue
            val currentIds = lines[i].noteIds
            if (currentIds.isEmpty() || currentIds.first() != trackerId) {
                // Set primary noteId from tracker, keep secondary IDs from merges
                lines[i].noteIds = listOf(trackerId) + currentIds.drop(1).filter { it != trackerId }
            }
        }
    }

    /**
     * Initializes the editor from note lines with their associated noteIds.
     * Used when loading a note from Firestore.
     */
    internal fun initFromNoteLines(noteLines: List<Pair<String, List<String>>>) {
        lines.clear()
        noteLines.forEach { (text, noteIds) ->
            lines.add(LineState(text, text.length, noteIds))
        }
        focusedLineIndex = 0
    }

    /**
     * Removes the empty line at the cursor position if it exists.
     * This cleans up empty lines created by deletion without affecting
     * pre-existing empty lines elsewhere in the document.
     *
     * @param cursorPosition The global character offset where the cursor is after deletion
     */
    private fun removeEmptyLineAtCursor(cursorPosition: Int) {
        if (lines.size <= 1) return

        val (lineIndex, _) = getLineAndLocalOffset(cursorPosition)

        // Don't remove the last line (keep for typing convenience)
        if (lineIndex >= lines.lastIndex) return

        // Only remove if the line at cursor is empty
        if (lines[lineIndex].text.isEmpty()) {
            lines.removeAt(lineIndex)
        }
    }

    internal fun notifyChange() {
        onTextChange?.invoke(text)
    }
}

/**
 * Creates and remembers an EditorState.
 */
@Composable
fun rememberEditorState(): EditorState {
    return remember { EditorState() }
}
