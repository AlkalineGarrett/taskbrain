package org.alkaline.taskbrain.ui.currentnote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import org.alkaline.taskbrain.data.NoteIdSentinel
import org.alkaline.taskbrain.ui.currentnote.move.MoveExecutor
import org.alkaline.taskbrain.ui.currentnote.move.MoveTargetFinder
import org.alkaline.taskbrain.ui.currentnote.selection.EditorSelection
import org.alkaline.taskbrain.ui.currentnote.selection.SelectionCoordinates
import org.alkaline.taskbrain.data.NoteLine
import org.alkaline.taskbrain.ui.currentnote.util.IndentationUtils

/**
 * State holder for the entire editor.
 */
class EditorState {
    internal val lines: SnapshotStateList<LineState> = mutableStateListOf(LineState(""))
    var focusedLineIndex by mutableIntStateOf(0)
        internal set

    var parentNoteId: String = ""
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
     *
     * Surgical implementation: only the lines actually touched by the selection are
     * modified. Lines fully outside the selection keep their LineState (and noteIds)
     * untouched — no lossy text matching needed. See [removeSelectedRange] for details.
     */
    internal fun deleteSelectionInternal(): Int {
        if (!hasSelection) return -1

        val fullText = text
        val (selStart, selEnd) = getEffectiveSelectionRange(fullText)
        val (startLineIdx, startLocalOffset) = getLineAndLocalOffset(selStart)
        val (endLineIdx, endLocalOffset) = getLineAndLocalOffset(selEnd)

        removeSelectedRange(startLineIdx, startLocalOffset, endLineIdx, endLocalOffset)

        clearSelection()
        focusedLineIndex = startLineIdx.coerceIn(0, lines.lastIndex.coerceAtLeast(0))
        lines.getOrNull(focusedLineIndex)?.let { line ->
            line.updateFull(line.text, startLocalOffset.coerceIn(0, line.text.length))
        }

        requestFocusUpdate()
        notifyChange()
        return selStart
    }

    /**
     * Replaces the current selection with new text.
     * If no selection, inserts at the current cursor position.
     * Returns the new cursor position (at the end of inserted text).
     * Internal: Use EditorController.paste() for proper undo handling.
     *
     * Surgical implementation: same as [deleteSelectionInternal] for the deletion phase,
     * then inserts the replacement at the cursor. Lines outside the selection are not
     * touched and keep their noteIds. The inserted lines (between any newlines in the
     * replacement) get empty noteIds, since they are new content. See [removeSelectedRange]
     * and [insertTextAt] for details.
     */
    internal fun replaceSelectionInternal(replacement: String): Int {
        // 1. Resolve the deletion range, then delete it (no-op if no selection).
        val anchorLineIdx: Int
        val anchorLocalOffset: Int
        if (hasSelection) {
            val fullText = text
            val (selStart, selEnd) = getEffectiveSelectionRange(fullText)
            val (startLineIdx, startLocalOffset) = getLineAndLocalOffset(selStart)
            val (endLineIdx, endLocalOffset) = getLineAndLocalOffset(selEnd)
            removeSelectedRange(startLineIdx, startLocalOffset, endLineIdx, endLocalOffset)
            anchorLineIdx = startLineIdx.coerceIn(0, lines.lastIndex.coerceAtLeast(0))
            anchorLocalOffset = startLocalOffset.coerceIn(
                0,
                lines.getOrNull(anchorLineIdx)?.text?.length ?: 0,
            )
            clearSelection()
        } else {
            val currentLine = lines.getOrNull(focusedLineIndex) ?: return 0
            anchorLineIdx = focusedLineIndex
            anchorLocalOffset = currentLine.cursorPosition
        }

        // 2. Insert the replacement at (anchorLineIdx, anchorLocalOffset).
        val (cursorLineIdx, cursorLocalOffset) = insertTextAt(
            anchorLineIdx,
            anchorLocalOffset,
            replacement,
        )

        focusedLineIndex = cursorLineIdx
        lines.getOrNull(cursorLineIdx)?.let { line ->
            line.updateFull(line.text, cursorLocalOffset.coerceIn(0, line.text.length))
        }

        // Compute the global cursor offset to return.
        val newCursorPos = getLineStartOffset(cursorLineIdx) + cursorLocalOffset
        removeEmptyLineAtCursor(newCursorPos)

        requestFocusUpdate()
        notifyChange()
        return newCursorPos
    }

    /**
     * Removes the text in `[startLineIdx@startLocalOffset, endLineIdx@endLocalOffset)`
     * from the line list, mutating only the affected lines.
     *
     * Lines fully outside the range are not touched (their `LineState`s — including
     * `noteIds` — are preserved by reference). The start line is shrunk to its prefix
     * portion (and merged with the end line's suffix portion, if any), and lines in
     * `(startLineIdx..endLineIdx]` are removed.
     *
     * NoteId policy after merge:
     * - If the surviving content comes entirely from the start line, keep its noteIds.
     * - If the surviving content comes entirely from the end line (start prefix is empty
     *   and end suffix is non-empty), inherit the end line's noteIds.
     * - If the surviving content is empty, keep the start line's noteIds.
     * - Mixed (both prefixes have content), keep the start line's noteIds — the end
     *   line's noteId is dropped because the surviving line "is" the start line.
     */
    private fun removeSelectedRange(
        startLineIdx: Int,
        startLocalOffset: Int,
        endLineIdx: Int,
        endLocalOffset: Int,
    ) {
        if (startLineIdx == endLineIdx) {
            val line = lines.getOrNull(startLineIdx) ?: return
            val newText = line.text.substring(0, startLocalOffset) +
                line.text.substring(endLocalOffset.coerceAtMost(line.text.length))
            line.updateFull(newText, startLocalOffset)
            return
        }

        val startLine = lines.getOrNull(startLineIdx) ?: return
        val endLine = lines.getOrNull(endLineIdx) ?: return
        val startPart = startLine.text.substring(0, startLocalOffset.coerceAtMost(startLine.text.length))
        val endPart = endLine.text.substring(endLocalOffset.coerceAtMost(endLine.text.length))
        val newStartText = startPart + endPart
        val newNoteIds = when {
            startPart.isEmpty() && endPart.isNotEmpty() -> endLine.noteIds
            else -> startLine.noteIds
        }
        startLine.updateFull(newStartText, startLocalOffset)
        startLine.noteIds = newNoteIds

        // Remove lines (startLineIdx, endLineIdx] in reverse so indices stay valid.
        for (i in endLineIdx downTo startLineIdx + 1) {
            lines.removeAt(i)
        }
    }

    /**
     * Inserts [replacement] into the line list at `lineIdx@localOffset`. Returns the
     * `(lineIdx, localOffset)` of the cursor after insertion (the end of the inserted text).
     *
     * Single-line replacements modify only the target line in place. Multi-line
     * replacements split the target line at the cursor, with the first replacement
     * line appended to the prefix portion and the last replacement line prepended to
     * the suffix portion. Newly created lines (everything between the first and last
     * replacement line) get empty `noteIds` — they are new content. The original line's
     * `noteIds` are preserved on the line containing its prefix portion.
     */
    private fun insertTextAt(
        lineIdx: Int,
        localOffset: Int,
        replacement: String,
    ): Pair<Int, Int> {
        val line = lines.getOrNull(lineIdx) ?: return lineIdx to localOffset
        val replacementLines = replacement.split("\n")

        if (replacementLines.size == 1) {
            val newText = line.text.substring(0, localOffset) +
                replacement +
                line.text.substring(localOffset)
            line.updateFull(newText, localOffset + replacement.length)
            return lineIdx to localOffset + replacement.length
        }

        // Multi-line: split the target line at the cursor.
        val prefix = line.text.substring(0, localOffset)
        val suffix = line.text.substring(localOffset)

        // Original line keeps its noteIds and gets `prefix + first replacement line`.
        val firstNewText = prefix + replacementLines.first()
        line.updateFull(firstNewText, firstNewText.length)
        // line.noteIds intentionally unchanged.

        // Middle replacement lines are new content — stamp SURGICAL sentinels so
        // the save layer can attribute fresh-doc allocations to this path.
        var insertAt = lineIdx + 1
        for (i in 1 until replacementLines.size - 1) {
            lines.add(insertAt, LineState(
                replacementLines[i], replacementLines[i].length,
                listOf(NoteIdSentinel.new(NoteIdSentinel.Origin.SURGICAL)),
            ))
            insertAt++
        }

        // The last replacement line + the original suffix becomes the cursor line.
        val lastReplacement = replacementLines.last()
        val lastLineText = lastReplacement + suffix
        // Fresh doc for the tail: the original line's id went with its prefix half
        // (we mutated it in place above), so the tail here is new content.
        lines.add(insertAt, LineState(
            lastLineText, lastReplacement.length,
            listOf(NoteIdSentinel.new(NoteIdSentinel.Origin.SURGICAL)),
        ))

        return insertAt to lastReplacement.length
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
            val noteIds = reorderedNoteIds.getOrElse(index) {
                // Reorder produced more result-lines than input; fill with a
                // SURGICAL sentinel so the save layer allocates a fresh doc.
                listOf(NoteIdSentinel.new(NoteIdSentinel.Origin.SURGICAL))
            }
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

    // =========================================================================
    // Surgical mutations for directive-driven content rewrites
    //
    // These are the safe replacement for `editorState.text.replace(...) → updateFromText(...)`,
    // which used to round-trip the entire editor through lossy text matching. Each helper
    // modifies only the lines that actually change and preserves noteIds on every other line.
    // =========================================================================

    /**
     * Replaces the first line's full text with [newContent], preserving its noteIds.
     *
     * If [newContent] contains newlines, the additional lines are inserted as new
     * `LineState`s right after the first line, pushing existing content down. The newly
     * inserted lines get empty noteIds — they will be allocated fresh ids on save.
     *
     * Use for directive `CONTENT_CHANGED` mutations that update the host note's title /
     * first line from a directive runtime.
     */
    internal fun replaceFirstLineContent(newContent: String) {
        val newLines = newContent.split("\n")
        if (lines.isEmpty()) {
            // Defensive: shouldn't happen because EditorState always starts with a single
            // empty LineState, but seed the editor with the new content if it does.
            // The first new line gets parentNoteId (if known) so save still attaches the
            // parent doc; subsequent lines get DIRECTIVE sentinels (allocated fresh on save).
            val firstIds = if (parentNoteId.isNotEmpty()) listOf(parentNoteId) else emptyList()
            lines.add(LineState(newLines[0], newLines[0].length, firstIds))
            for (i in 1 until newLines.size) {
                lines.add(LineState(newLines[i], newLines[i].length, listOf(
                    NoteIdSentinel.new(NoteIdSentinel.Origin.DIRECTIVE)
                )))
            }
            notifyChange()
            return
        }
        // Mutate line 0 in place — noteIds preserved.
        lines[0].updateFull(newLines[0], newLines[0].length)
        // Insert any additional lines from the new content right after line 0.
        for (i in 1 until newLines.size) {
            lines.add(i, LineState(newLines[i], newLines[i].length, listOf(
                NoteIdSentinel.new(NoteIdSentinel.Origin.DIRECTIVE)
            )))
        }
        notifyChange()
    }

    /**
     * Appends [content] to the editor as one or more new lines.
     *
     * If [content] contains newlines, each segment becomes its own `LineState`. All
     * appended lines get empty noteIds — they are new content and will be allocated
     * fresh ids on save. Existing lines are not touched.
     *
     * Mirrors the behavior of `userContent + "\n" + content` followed by reload, but
     * without going through the lossy text-matching path.
     */
    internal fun appendContent(content: String) {
        val newLines = content.split("\n")
        for (line in newLines) {
            lines.add(LineState(line, line.length, listOf(
                NoteIdSentinel.new(NoteIdSentinel.Origin.DIRECTIVE)
            )))
        }
        notifyChange()
    }

    /**
     * Replaces every occurrence of [oldDirective] with [newDirective] across all lines,
     * modifying each affected line in place. Lines that don't contain [oldDirective] are
     * not touched and keep their `LineState` (and `noteIds`) untouched.
     *
     * Use for directive type-switches (e.g. `[alarm("id")] → [recurringAlarm("recId")]`)
     * and for the redo-of-alarm-create id patch path.
     */
    internal fun replaceDirectiveText(oldDirective: String, newDirective: String) {
        if (oldDirective.isEmpty() || oldDirective == newDirective) return
        var changed = false
        for (line in lines) {
            if (!line.content.contains(oldDirective)) continue
            val newLineContent = line.content.replace(oldDirective, newDirective)
            line.updateContent(newLineContent, line.contentCursorPosition.coerceAtMost(newLineContent.length))
            changed = true
        }
        if (changed) notifyChange()
    }

    @Deprecated(
        "Lossy: reconciles noteIds by content match and silently drops any line whose " +
            "content doesn't match an old line. Use initFromNoteLines(…, preserveCursor = true) " +
            "to re-seed from a structured NoteLine list, or use one of the surgical mutation " +
            "helpers (replaceFirstLineContent, replaceDirectiveText, …) to modify specific lines.",
        level = DeprecationLevel.WARNING,
    )
    internal fun updateFromText(newText: String) {
        val oldNoteIds = lines.map { it.noteIds }
        val oldContents = lines.map { it.text }
        val newLines = newText.split("\n")

        val unmatched = mutableListOf<Pair<Int, String>>()
        val reconciled = org.alkaline.taskbrain.data.reconcileLineNoteIds(
            oldContents = oldContents,
            oldNoteIds = oldNoteIds,
            newContents = newLines,
            onUnmatchedNonEmpty = { idx, content -> unmatched.add(idx to content) },
        )
        val withParent = org.alkaline.taskbrain.data.enforceParentNoteId(reconciled, parentNoteId)

        if (parentNoteId.isEmpty() && newLines.isNotEmpty() && newLines[0].isNotEmpty()) {
            android.util.Log.w(
                "LineReconciliation",
                "EditorState.updateFromText: parentNoteId is empty — line[0] will not get parent enforcement. " +
                    "newLines.size=${newLines.size}, oldLines.size=${oldContents.size}, " +
                    "first='${newLines[0].take(60)}'"
            )
        }
        if (unmatched.isNotEmpty()) {
            android.util.Log.w(
                "LineReconciliation",
                "EditorState.updateFromText: ${unmatched.size} non-empty new line(s) lost noteIds " +
                    "(no exact or similarity match). " +
                    "oldLines.size=${oldContents.size}, newLines.size=${newLines.size}. " +
                    "First: ${unmatched.take(3).joinToString { "[${it.first}] '${it.second.take(40)}'" }}"
            )
        }

        lines.clear()
        newLines.forEachIndexed { index, lineText ->
            lines.add(LineState(lineText, lineText.length, withParent[index]))
        }
        focusedLineIndex = focusedLineIndex.coerceIn(0, lines.lastIndex.coerceAtLeast(0))
    }

    /**
     * Initializes the editor from note lines with their associated noteIds.
     * Used when loading a note from Firestore.
     *
     * @param preserveCursor If true, attempts to restore the cursor to the same
     *   line (by noteId) and position. Used for external change reloads where
     *   the user's cursor should stay in place.
     */
    internal fun initFromNoteLines(
        noteLines: List<Pair<String, List<String>>>,
        preserveCursor: Boolean = false,
    ) {
        // Capture cursor state before clearing
        val prevLineIndex = focusedLineIndex
        val prevNoteId = lines.getOrNull(prevLineIndex)?.noteIds?.firstOrNull()
        val prevCursorPos = lines.getOrNull(prevLineIndex)?.cursorPosition ?: 0

        parentNoteId = noteLines.firstOrNull()?.second?.firstOrNull() ?: ""
        lines.clear()
        noteLines.forEach { (text, noteIds) ->
            lines.add(LineState(text, text.length, noteIds))
        }

        if (preserveCursor && prevNoteId != null) {
            // Find the line with the same noteId
            val restoredIndex = lines.indexOfFirst { it.noteIds.firstOrNull() == prevNoteId }
            if (restoredIndex >= 0) {
                focusedLineIndex = restoredIndex
                lines[restoredIndex].moveCursor(prevCursorPos)
            } else {
                // Line disappeared — find the closest surviving line
                focusedLineIndex = prevLineIndex.coerceAtMost(lines.lastIndex).coerceAtLeast(0)
            }
        } else {
            focusedLineIndex = 0
        }
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

    /**
     * Converts the current editor lines to a list of [NoteLine]s ready for save.
     *
     * This is the canonical "build save shape from the editor" function. Save callers
     * should always go through here rather than zipping `text` and
     * `lines.map { it.noteIds }` separately, which is fragile when content and noteIds
     * drift apart.
     *
     * Behavior:
     * - Resolves duplicate noteIds via [resolveNoteIds] (longest-content wins)
     * - Enforces [parentNoteId] as the primary id of line 0
     * - Logs a warning if [parentNoteId] is empty (line 0 will not get its parent id)
     *   or if many non-empty lines have no noteId (likely indicates corruption)
     */
    fun toNoteLines(): List<NoteLine> {
        val contentLines = lines.map { it.text }
        val lineNoteIds = lines.map { it.noteIds }
        val tracked = resolveNoteIds(contentLines, lineNoteIds).toMutableList()

        if (parentNoteId.isNotEmpty() && tracked.isNotEmpty() && tracked[0].noteId != parentNoteId) {
            tracked[0] = tracked[0].copy(noteId = parentNoteId)
        }

        // Diagnostics: warn loudly if the editor's noteIds are mostly empty at save time.
        // This is the symptom of the content-drop bug — log it BEFORE the save guard fires
        // so we can correlate with the upstream cause.
        val nonEmptyLines = tracked.count { it.content.isNotEmpty() }
        val nonEmptyMissingId = tracked.count { it.content.isNotEmpty() && it.noteId == null }
        if (parentNoteId.isEmpty() && nonEmptyLines > 0) {
            android.util.Log.w(
                "LineReconciliation",
                "EditorState.toNoteLines: parentNoteId is empty — line[0] will save with no parent id. " +
                    "lines.size=${lines.size}, nonEmpty=$nonEmptyLines"
            )
        }
        if (nonEmptyLines >= 3 && nonEmptyMissingId >= nonEmptyLines / 2) {
            android.util.Log.w(
                "LineReconciliation",
                "EditorState.toNoteLines: $nonEmptyMissingId of $nonEmptyLines non-empty lines have no noteId. " +
                    "This will cause new docs to be allocated on save. " +
                    "parentNoteId='$parentNoteId'. " +
                    "First missing: ${
                        tracked.withIndex()
                            .filter { it.value.content.isNotEmpty() && it.value.noteId == null }
                            .take(3)
                            .joinToString { "[${it.index}] '${it.value.content.take(40)}'" }
                    }"
            )
        }

        return tracked
    }
}

/**
 * Creates and remembers an EditorState.
 */
@Composable
fun rememberEditorState(): EditorState {
    return remember { EditorState() }
}
