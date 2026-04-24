package org.alkaline.taskbrain.ui.currentnote

import androidx.compose.runtime.Composable
import org.alkaline.taskbrain.data.NoteIdSentinel
import org.alkaline.taskbrain.ui.currentnote.undo.CommandType
import org.alkaline.taskbrain.ui.currentnote.undo.UndoManager
import org.alkaline.taskbrain.ui.currentnote.undo.AlarmSnapshot
import org.alkaline.taskbrain.ui.currentnote.undo.UndoSnapshot
import org.alkaline.taskbrain.ui.currentnote.selection.SelectionCoordinates
import org.alkaline.taskbrain.ui.currentnote.util.ClipboardParser
import org.alkaline.taskbrain.ui.currentnote.util.CompletedLineUtils
import org.alkaline.taskbrain.ui.currentnote.util.LinePrefixes
import org.alkaline.taskbrain.ui.currentnote.util.PasteHandler
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString

/**
 * Operation types for discrete editing actions.
 *
 * NOTE: TYPING, ENTER, and LINE_MERGE are NOT in this enum because they have
 * specialized handling:
 * - TYPING: Implicit via focus changes (already works correctly)
 * - ENTER: Already correctly handled in splitLine()
 * - LINE_MERGE: Already correctly handled in mergeToPreviousLine()/mergeNextLine()
 */
enum class OperationType {
    COMMAND_BULLET,   // Each press is separate undo step
    COMMAND_CHECKBOX, // Each press is separate undo step
    COMMAND_INDENT,   // Consecutive indent/unindent are grouped
    PASTE,            // Always own undo step
    CUT,              // Always own undo step
    DELETE_SELECTION, // Always own undo step
    CHECKBOX_TOGGLE,  // Gutter checkbox toggle - same as COMMAND_CHECKBOX
    ALARM_SYMBOL,     // Alarm symbol insertion - commits pending, captures state
    MOVE_LINES,       // Consecutive moves of same lines are grouped
    DIRECTIVE_EDIT,   // Confirming directive edit row - always separate undo step
    SORT_COMPLETED,   // Save-time sort of checked lines to bottom - always separate undo step
}

/**
 * State for move up/down buttons.
 */
data class MoveButtonState(
    val isEnabled: Boolean,
    val isWarning: Boolean
)

/**
 * Centralized controller for all editor state modifications.
 *
 * This is the SINGLE CHANNEL through which all state changes flow.
 * Benefits:
 * - Single source of truth
 * - Predictable state transitions
 * - Easy debugging (all changes go through one place)
 * - Proper notification of changes
 *
 * Usage:
 * - ImeState forwards all IME events here
 * - UI components call controller methods instead of directly modifying state
 * - State changes automatically trigger recomposition via stateVersion
 */
class EditorController(
    internal val state: EditorState,
    val undoManager: UndoManager = UndoManager()
) {
    /** Hidden line indices (completed lines when showCompleted=false). Set by the UI layer. */
    var hiddenIndices: Set<Int> = emptySet()

    /**
     * Line indices that were recently toggled from unchecked to checked.
     * These stay visible (at reduced opacity) until the next save.
     */
    val recentlyCheckedIndices: MutableSet<Int> = mutableSetOf()

    companion object {
        /** Shared across all EditorController instances so cut in one embedded note
         *  can recover noteIds when pasted into a different embedded note. */
        private var sharedCutLines: List<LineState> = emptyList()
    }

    // =========================================================================
    // Undo/Redo Operations
    // =========================================================================

    val canUndo: Boolean get() = undoManager.canUndo
    val canRedo: Boolean get() = undoManager.canRedo

    /**
     * Commits any pending undo state. Call at undo boundaries:
     * - Focus changes to different line
     * - Save button clicked
     * - Undo/redo button clicked
     * - Navigate away from screen
     *
     * @param continueEditing If true, sets up a new pending snapshot for subsequent
     *        typing on the current line. Use this for save operations where the user
     *        continues editing. Don't use for navigate-away or undo/redo (which handle
     *        this separately).
     */
    fun commitUndoState(continueEditing: Boolean = false) {
        undoManager.commitPendingUndoState(state)
        if (continueEditing) {
            undoManager.beginEditingLine(state, state.focusedLineIndex)
        }
    }

    /**
     * Records a command bar action for undo grouping.
     * Bullet/Checkbox: Each is a separate undo step.
     * Indent/Unindent: Consecutive presses are grouped.
     */
    fun recordCommand(type: CommandType) {
        undoManager.recordCommand(state, type)
    }

    /**
     * Call after command completes for commands that should immediately commit.
     */
    fun commitAfterCommand(type: CommandType) {
        undoManager.commitAfterCommand(state, type)
    }

    /**
     * Records that an alarm was created, for undo/redo tracking.
     * Ensures there's an undo entry to attach the alarm to.
     */
    fun recordAlarmCreation(alarm: AlarmSnapshot) {
        undoManager.recordAlarmCreation(alarm, state)
    }

    /**
     * Performs undo and restores the snapshot.
     * Returns the snapshot for alarm handling (caller deletes createdAlarm if present).
     */
    fun undo(): UndoSnapshot? {
        val snapshot = undoManager.undo(state) ?: return null
        restoreFromSnapshot(snapshot)
        // Set up pending state for subsequent typing on the restored line
        undoManager.beginEditingLine(state, state.focusedLineIndex)
        return snapshot
    }

    /**
     * Performs redo and restores the snapshot.
     * Returns the snapshot for alarm handling (caller recreates createdAlarm if present).
     */
    fun redo(): UndoSnapshot? {
        val snapshot = undoManager.redo(state) ?: return null
        restoreFromSnapshot(snapshot)
        // Set up pending state for subsequent typing on the restored line
        undoManager.beginEditingLine(state, state.focusedLineIndex)
        return snapshot
    }

    /**
     * Updates the alarm ID after redo recreates an alarm with a new ID.
     */
    fun updateLastUndoAlarmId(newId: String) {
        undoManager.updateLastUndoAlarmId(newId)
    }

    /**
     * Restores editor state from a snapshot.
     */
    private fun restoreFromSnapshot(snapshot: UndoSnapshot) {
        if (snapshot.lineContents.size != snapshot.lineNoteIds.size) {
            android.util.Log.e(
                "EditorController",
                "restoreFromSnapshot: lineContents.size=${snapshot.lineContents.size} " +
                    "!= lineNoteIds.size=${snapshot.lineNoteIds.size} — " +
                    "undo snapshot is corrupt; some lines will lose their noteIds",
            )
        }
        state.lines.clear()
        snapshot.lineContents.forEachIndexed { index, lineText ->
            val noteIds = snapshot.lineNoteIds.getOrElse(index) { emptyList() }
            state.lines.add(LineState(lineText, lineText.length, noteIds))
        }
        state.focusedLineIndex = snapshot.focusedLineIndex.coerceIn(0, state.lines.lastIndex.coerceAtLeast(0))
        state.lines.getOrNull(state.focusedLineIndex)?.let { line ->
            line.updateFull(line.text, snapshot.cursorPosition.coerceIn(0, line.text.length))
        }
        state.clearSelection()
        state.requestFocusUpdate()
        state.notifyChange()
    }

    /**
     * Resets undo history. Call when loading a new note.
     */
    fun resetUndoHistory() {
        undoManager.reset()
    }

    // =========================================================================
    // Operation Executor (centralizes undo boundary management)
    // =========================================================================

    /**
     * Executes an operation with proper undo boundary handling.
     * Pre-operation: Sets up appropriate undo state based on operation type
     * Post-operation: Commits or continues pending state as appropriate
     */
    private fun <T> executeOperation(type: OperationType, action: () -> T): T {
        handlePreOperation(type)
        val result = action()
        handlePostOperation(type)
        return result
    }

    private fun handlePreOperation(type: OperationType) {
        when (type) {
            OperationType.COMMAND_BULLET, OperationType.COMMAND_CHECKBOX,
            OperationType.CHECKBOX_TOGGLE -> {
                undoManager.recordCommand(state, commandTypeFor(type))
            }
            OperationType.COMMAND_INDENT -> {
                undoManager.recordCommand(state, CommandType.INDENT)
            }
            OperationType.PASTE, OperationType.CUT, OperationType.DELETE_SELECTION -> {
                undoManager.captureStateBeforeChange(state)
            }
            OperationType.ALARM_SYMBOL -> {
                undoManager.commitPendingUndoState(state)
                undoManager.captureStateBeforeChange(state)
            }
            OperationType.MOVE_LINES -> {
                // Move lines has its own undo handling via recordMoveCommand
            }
            OperationType.DIRECTIVE_EDIT, OperationType.SORT_COMPLETED -> {
                undoManager.commitPendingUndoState(state)
                undoManager.captureStateBeforeChange(state)
            }
        }
    }

    private fun handlePostOperation(type: OperationType) {
        when (type) {
            OperationType.COMMAND_BULLET, OperationType.COMMAND_CHECKBOX,
            OperationType.CHECKBOX_TOGGLE -> {
                undoManager.commitAfterCommand(state, commandTypeFor(type))
            }
            OperationType.PASTE -> {
                undoManager.commitPendingUndoState(state)
            }
            OperationType.CUT, OperationType.DELETE_SELECTION -> {
                undoManager.beginEditingLine(state, state.focusedLineIndex)
            }
            OperationType.COMMAND_INDENT, OperationType.ALARM_SYMBOL -> {
                // Nothing - stays pending for grouping or alarm recording happens separately
            }
            OperationType.MOVE_LINES -> {
                // Move lines has its own undo handling via recordMoveCommand/updateMoveRange
            }
            OperationType.DIRECTIVE_EDIT, OperationType.SORT_COMPLETED -> {
                undoManager.beginEditingLine(state, state.focusedLineIndex)
            }
        }
    }

    private fun commandTypeFor(type: OperationType): CommandType = when (type) {
        OperationType.COMMAND_BULLET -> CommandType.BULLET
        OperationType.COMMAND_CHECKBOX, OperationType.CHECKBOX_TOGGLE -> CommandType.CHECKBOX
        OperationType.COMMAND_INDENT -> CommandType.INDENT
        else -> CommandType.OTHER
    }

    // =========================================================================
    // Command Operations (moved from EditorState, wrapped with undo handling)
    // =========================================================================

    /**
     * Toggles bullet prefix on the current line.
     * Each press is a separate undo step.
     */
    fun toggleBullet() = executeOperation(OperationType.COMMAND_BULLET) {
        state.toggleBulletInternal()
    }

    /**
     * Toggles checkbox prefix on the current line.
     * Cycles: nothing → unchecked → checked → removed
     * Each press is a separate undo step.
     */
    fun toggleCheckbox() {
        val range = state.getSelectedLineRange()
        val uncheckedBefore = range.associateWith { i ->
            state.lines.getOrNull(i)?.prefix
                ?.contains(LinePrefixes.CHECKBOX_UNCHECKED) == true
        }
        executeOperation(OperationType.COMMAND_CHECKBOX) {
            state.toggleCheckboxInternal()
        }
        for ((lineIndex, wasUnchecked) in uncheckedBefore) {
            trackRecentlyChecked(lineIndex, wasUnchecked)
        }
    }

    /**
     * Indents selected lines or current line.
     * Consecutive indent/unindent presses are grouped into one undo step.
     */
    fun indent() = executeOperation(OperationType.COMMAND_INDENT) {
        state.indentInternal(hiddenIndices)
    }

    /**
     * Unindents selected lines or current line.
     * Consecutive indent/unindent presses are grouped into one undo step.
     */
    fun unindent() = executeOperation(OperationType.COMMAND_INDENT) {
        state.unindentInternal(hiddenIndices)
    }

    /**
     * Pastes text with structure-aware handling (prefix merging, indent shifting, etc.).
     * See docs/paste-requirements.md for the full specification.
     */
    fun paste(plainText: String, html: String? = null) = executeOperation(OperationType.PASTE) {
        val parsed = ClipboardParser.parse(plainText, html)
        val cutLines = sharedCutLines
        sharedCutLines = emptyList()
        val result = PasteHandler.execute(
            state.lines.toList(),
            state.focusedLineIndex,
            state.selection,
            parsed,
            cutLines.takeIf { it.isNotEmpty() },
        )
        state.lines.clear()
        state.lines.addAll(result.lines)
        state.focusedLineIndex = result.cursorLineIndex
        state.lines.getOrNull(result.cursorLineIndex)?.updateFull(
            state.lines[result.cursorLineIndex].text,
            result.cursorPosition,
        )
        state.clearSelection()
        state.requestFocusUpdate()
        state.notifyChange()
    }

    /**
     * Cuts selected text to clipboard.
     * Always creates its own undo step.
     * Returns the cut text, or null if nothing was selected.
     */
    fun cutSelection(clipboard: ClipboardManager): String? {
        if (!state.hasSelection) return null
        return executeOperation(OperationType.CUT) {
            // Capture cut lines (with noteIds) before deletion for paste recovery
            val range = state.getSelectedLineRange()
            sharedCutLines = state.lines.subList(range.first, range.last + 1)
                .map { LineState(it.text, noteIds = it.noteIds.toList()) }

            val clipText = getSelectedTextWithPrefix()
            if (clipText.isNotEmpty()) {
                clipboard.setText(AnnotatedString(clipText))
                state.deleteSelectionInternal()
            }
            clipText.takeIf { it.isNotEmpty() }
        }
    }

    /**
     * Deletes selected text.
     * Always creates its own undo step.
     */
    fun deleteSelectionWithUndo() {
        if (!state.hasSelection) return
        executeOperation(OperationType.DELETE_SELECTION) {
            state.deleteSelectionInternal()
        }
    }

    /**
     * Inserts text at the end of the current line (for alarm symbol).
     * Commits pending state and creates an undo point.
     */
    fun insertAtEndOfCurrentLine(text: String) =
        executeOperation(OperationType.ALARM_SYMBOL) {
            state.insertAtEndOfCurrentLineInternal(text)
        }

    /**
     * Confirms a directive edit from the edit row.
     * Creates an undo point so the change can be undone.
     *
     * @param lineIndex The line containing the directive
     * @param startOffset Start position within line content (inclusive)
     * @param endOffset End position within line content (exclusive)
     * @param newText New directive source text (e.g., "[99]" replacing "[42]")
     */
    fun confirmDirectiveEdit(lineIndex: Int, startOffset: Int, endOffset: Int, newText: String) =
        executeOperation(OperationType.DIRECTIVE_EDIT) {
            val line = state.lines.getOrNull(lineIndex) ?: return@executeOperation
            val content = line.content

            // Validate range
            if (startOffset < 0 || endOffset > content.length || startOffset > endOffset) {
                return@executeOperation
            }

            // Replace directive text in content (preserves prefix)
            val newContent = content.substring(0, startOffset) +
                             newText +
                             content.substring(endOffset)

            line.updateContent(newContent, line.contentCursorPosition)
            state.notifyChange()
        }

    // =========================================================================
    // Move Lines Operations
    // =========================================================================

    /**
     * Gets the current state of the move up button.
     */
    fun getMoveUpState(): MoveButtonState {
        val target = state.getMoveTarget(moveUp = true, hiddenIndices = hiddenIndices)
        return MoveButtonState(
            isEnabled = target != null,
            isWarning = state.wouldOrphanChildren()
        )
    }

    /**
     * Gets the current state of the move down button.
     */
    fun getMoveDownState(): MoveButtonState {
        val target = state.getMoveTarget(moveUp = false, hiddenIndices = hiddenIndices)
        return MoveButtonState(
            isEnabled = target != null,
            isWarning = state.wouldOrphanChildren()
        )
    }

    /**
     * Moves the current line/selection up.
     * Consecutive moves of the same lines are grouped into one undo step.
     * @return true if the move was performed, false if at boundary
     */
    fun moveUp(): Boolean {
        val range = if (state.hasSelection) state.getSelectedLineRange() else state.getLogicalBlock(state.focusedLineIndex)
        val target = state.getMoveTarget(moveUp = true, hiddenIndices = hiddenIndices) ?: return false

        // Record for undo grouping (uses range BEFORE move for grouping check)
        undoManager.recordMoveCommand(state, range)

        // Perform the move
        val newRange = state.moveLinesInternal(range, target)
        if (newRange != null) {
            // Update the tracked move range to the new position
            undoManager.updateMoveRange(newRange)
            // Mark content as changed so canUndo returns true
            undoManager.markContentChanged()
        }

        return newRange != null
    }

    /**
     * Moves the current line/selection down.
     * Consecutive moves of the same lines are grouped into one undo step.
     * @return true if the move was performed, false if at boundary
     */
    fun moveDown(): Boolean {
        val range = if (state.hasSelection) state.getSelectedLineRange() else state.getLogicalBlock(state.focusedLineIndex)
        val target = state.getMoveTarget(moveUp = false, hiddenIndices = hiddenIndices) ?: return false

        // Record for undo grouping (uses range BEFORE move for grouping check)
        undoManager.recordMoveCommand(state, range)

        // Perform the move
        val newRange = state.moveLinesInternal(range, target)
        if (newRange != null) {
            // Update the tracked move range to the new position
            undoManager.updateMoveRange(newRange)
            // Mark content as changed so canUndo returns true
            undoManager.markContentChanged()
        }

        return newRange != null
    }

    /**
     * Sorts completed (checked) lines to the bottom of each sibling group.
     * Called at save time so undo restores the pre-sort order.
     * @return true if any reordering occurred
     */
    fun sortCompletedToBottom(): Boolean {
        recentlyCheckedIndices.clear()
        val currentTexts = state.lines.map { it.text }
        val permutation = CompletedLineUtils.sortCompletedToBottomIndexed(currentTexts)
        val sorted = permutation.map { currentTexts[it] }
        if (sorted == currentTexts) return false
        val oldNoteIds = state.lines.map { it.noteIds }
        val oldContentLengths = state.lines.map { it.noteIdContentLengths }
        executeOperation(OperationType.SORT_COMPLETED) {
            state.lines.forEachIndexed { i, line ->
                val origIndex = permutation[i]
                line.updateFull(sorted[i], line.cursorPosition.coerceIn(0, sorted[i].length))
                line.noteIds = oldNoteIds[origIndex]
                line.noteIdContentLengths = oldContentLengths[origIndex]
            }
            state.notifyChange()
        }
        return true
    }

    /**
     * Copies selected text to clipboard without modifying state.
     */
    fun copySelection(clipboard: ClipboardManager) {
        val clipText = getSelectedTextWithPrefix()
        if (clipText.isNotEmpty()) {
            clipboard.setText(AnnotatedString(clipText))
        }
    }

    /**
     * Returns the selected text, prepending the first line's prefix when the selection
     * starts at the beginning of content (right after the prefix). This ensures
     * cut/copy of full line content preserves structure (checkboxes, bullets, indentation),
     * while partial selections within content are returned as-is.
     */
    private fun getSelectedTextWithPrefix(): String {
        val text = state.getSelectedText()
        if (text.isEmpty()) return text

        val fullText = state.text
        val (selStart, _) = SelectionCoordinates.getEffectiveSelectionRange(fullText, state.selection)
        val (lineIndex, selLocalOffset) = state.getLineAndLocalOffset(selStart)
        val firstLine = state.lines.getOrNull(lineIndex) ?: return text
        val prefix = firstLine.prefix
        // Only prepend prefix when selection starts exactly at the content boundary
        // (right after the prefix), meaning the full content from the start is selected
        return if (prefix.isNotEmpty() && selLocalOffset == prefix.length) {
            prefix + text
        } else {
            text
        }
    }

    /**
     * Clears selection and positions cursor at selection start.
     */
    fun clearSelection() {
        val selectionStart = state.selection.min
        state.clearSelection()
        setCursorFromGlobalOffset(selectionStart)
    }

    // =========================================================================
    // Text Input Operations
    // =========================================================================

    /**
     * Insert text at the current cursor position.
     * If there's a selection, replaces it.
     */
    fun insertText(lineIndex: Int, text: String) {
        // Handle newline specially
        if (text.contains('\n')) {
            val parts = text.split('\n')
            parts.forEachIndexed { index, part ->
                if (index > 0) {
                    splitLine(lineIndex + index - 1)
                }
                if (part.isNotEmpty()) {
                    insertTextAtCursor(lineIndex + index, part)
                }
            }
            return
        }

        // If there's a selection, replace it
        if (state.hasSelection) {
            state.replaceSelectionInternal(text)
            return
        }

        // Normal insert at cursor
        insertTextAtCursor(lineIndex, text)
    }

    private fun insertTextAtCursor(lineIndex: Int, text: String) {
        val line = state.lines.getOrNull(lineIndex) ?: return
        val cursorInLine = line.cursorPosition
        val newText = line.text.substring(0, cursorInLine) + text + line.text.substring(cursorInLine)
        val newCursor = cursorInLine + text.length

        line.updateFull(newText, newCursor)
        state.requestFocusUpdate()
        state.notifyChange()
    }

    /**
     * Delete backward (backspace) from the current cursor position.
     */
    fun deleteBackward(lineIndex: Int) {
        // If there's a selection, delete it
        if (state.hasSelection) {
            state.deleteSelectionInternal()
            undoManager.markContentChanged()
            return
        }

        val line = state.lines.getOrNull(lineIndex) ?: return
        val cursor = line.cursorPosition

        // At start of line content (at or before prefix end)
        if (cursor <= line.prefix.length) {
            if (lineIndex <= 0) return
            // Skip hidden lines when merging backward
            var target = lineIndex - 1
            while (target >= 0 && target in hiddenIndices) target--
            if (target < 0) return

            val previousLine = state.lines.getOrNull(target) ?: return
            val currentHasContent = line.content.isNotEmpty()
            val previousHasContent = previousLine.content.isNotEmpty()

            when {
                // Previous has content: merge current's content onto previous
                previousHasContent -> mergeToPreviousLine(lineIndex, target)
                // Previous empty, current has content: delete the empty previous line
                currentHasContent -> deleteLineAndMergeIds(
                    survivor = line, other = previousLine,
                    removeIndex = target, focusIndex = target
                )
                // Neither has content: keep previous, delete current
                else -> deleteLineAndMergeIds(
                    survivor = previousLine, other = line,
                    removeIndex = lineIndex, focusIndex = target
                )
            }
            return
        }

        // Normal delete within content
        val newText = line.text.substring(0, cursor - 1) + line.text.substring(cursor)
        line.updateFull(newText, cursor - 1)
        state.requestFocusUpdate()
        state.notifyChange()
        undoManager.markContentChanged()
    }

    /**
     * Delete forward from the current cursor position.
     */
    fun deleteForward(lineIndex: Int) {
        if (state.hasSelection) {
            state.deleteSelectionInternal()
            undoManager.markContentChanged()
            return
        }

        val line = state.lines.getOrNull(lineIndex) ?: return
        val cursor = line.cursorPosition

        // At end of line - merge with next visible line (creates its own undo boundary)
        if (cursor >= line.text.length) {
            var target = lineIndex + 1
            while (target < state.lines.size && target in hiddenIndices) target++
            if (target >= state.lines.size) return
            val nextLine = state.lines.getOrNull(target) ?: return

            val currentHasContent = line.content.isNotEmpty()
            val nextHasContent = nextLine.content.isNotEmpty()

            when {
                currentHasContent -> mergeNextLine(lineIndex, target)
                nextHasContent -> deleteLineAndMergeIds(
                    survivor = nextLine, other = line,
                    removeIndex = lineIndex, focusIndex = lineIndex
                )
                else -> deleteLineAndMergeIds(
                    survivor = line, other = nextLine,
                    removeIndex = target, focusIndex = lineIndex
                )
            }
            return
        }

        // Normal delete
        val newText = line.text.substring(0, cursor) + line.text.substring(cursor + 1)
        line.updateFull(newText, cursor)
        state.requestFocusUpdate()
        state.notifyChange()
        undoManager.markContentChanged()
    }

    // =========================================================================
    // Line Operations
    // =========================================================================

    /**
     * Gets the prefix for a new line based on the current line's prefix.
     * When [preserveChecked] is false (splitting at end of line), converts checked → unchecked.
     * When true (splitting mid-line), keeps the checked state.
     */
    private fun getNewLinePrefix(currentPrefix: String, preserveChecked: Boolean): String {
        if (preserveChecked) return currentPrefix
        return currentPrefix.replace(LinePrefixes.CHECKBOX_CHECKED, LinePrefixes.CHECKBOX_UNCHECKED)
    }

    /**
     * Combines noteIds and content-length metadata from two lines being merged.
     * Preserves text order (lineA first, lineB second) so that splitNoteIds can
     * later distribute noteIds back to the correct halves.
     *
     * @param lineA the line whose content appears first in the merged text
     * @param lineB the line whose content appears second in the merged text
     */
    private fun mergeNoteIds(lineA: LineState, lineB: LineState): MergedNoteIds {
        val combined = (lineA.noteIds + lineB.noteIds).distinct()
        // A sentinel is a "needs a fresh doc" marker. If the merge brings in a
        // real id alongside a sentinel, the real id wins and the sentinel is
        // dropped — we don't need a fresh allocation for this merged line.
        val hasReal = combined.any { !NoteIdSentinel.isSentinel(it) }
        val allIds = if (hasReal) combined.filter { !NoteIdSentinel.isSentinel(it) } else combined
        val contentLengths = buildMergedContentLengths(lineA) + buildMergedContentLengths(lineB)
        // Drop contentLengths entries that corresponded to dropped sentinel ids —
        // keep the list aligned with the filtered id list.
        val alignedLengths = if (hasReal && contentLengths.size == combined.size) {
            combined.mapIndexedNotNull { idx, id ->
                if (NoteIdSentinel.isSentinel(id)) null else contentLengths[idx]
            }
        } else contentLengths
        return MergedNoteIds(allIds, alignedLengths)
    }

    /**
     * Extracts per-noteId content lengths from a line.
     * If the line already has content-length metadata (from a prior merge), uses that.
     * Otherwise creates a single entry spanning the full content.
     */
    private fun buildMergedContentLengths(line: LineState): List<Int> {
        if (line.noteIdContentLengths.isNotEmpty() && line.noteIdContentLengths.size == line.noteIds.size) {
            return line.noteIdContentLengths
        }
        // Single noteId (or no metadata) — entire content belongs to this line's noteIds
        return if (line.noteIds.isNotEmpty()) listOf(line.content.length) else emptyList()
    }

    private data class MergedNoteIds(val noteIds: List<String>, val contentLengths: List<Int>)

    /**
     * Creates a new line with prefix continuation and adds it after the specified line.
     * @param lineIndex Index of the current line
     * @param newLineContent Content for the new line (without prefix)
     * @param currentPrefix Prefix from the current line
     */
    private fun createNewLineWithPrefix(
        lineIndex: Int,
        newLineContent: String,
        currentPrefix: String,
        noteIds: List<String> = emptyList(),
        preserveChecked: Boolean = false
    ) {
        val newLinePrefix = getNewLinePrefix(currentPrefix, preserveChecked)
        val newLineText = newLinePrefix + newLineContent
        val newLineCursor = newLinePrefix.length

        state.lines.add(lineIndex + 1, LineState(newLineText, newLineCursor, noteIds))
        state.focusedLineIndex = lineIndex + 1
        state.requestFocusUpdate()
        state.notifyChange()
    }

    /**
     * Split line at cursor (Enter key).
     * Continues the prefix (indentation + bullet/checkbox) on the new line.
     * Checked checkboxes become unchecked on the new line.
     *
     * Undo behavior: Enter + any subsequent typing on the new line are grouped
     * as one undo step. Typing before Enter is a separate undo step.
     */
    fun splitLine(lineIndex: Int) {
        // Prepare for structural change - commits prior typing, captures pre-split state
        undoManager.prepareForStructuralChange(state)

        state.clearSelection()
        val line = state.lines.getOrNull(lineIndex) ?: return
        val cursor = line.cursorPosition
        val prefix = line.prefix
        val noteIds = line.noteIds

        val beforeCursor = line.text.substring(0, cursor)
        val afterCursor = line.text.substring(cursor)

        val beforeHasContent = beforeCursor.length > prefix.length
        val afterHasContent = afterCursor.isNotEmpty()
        val (currentNoteIds, newNoteIds) = org.alkaline.taskbrain.data.splitNoteIds(
            noteIds, beforeCursor.length - prefix.length, afterCursor.length,
            beforeHasContent, afterHasContent, line.noteIdContentLengths
        )

        // Update current line
        line.updateFull(beforeCursor, beforeCursor.length)
        line.noteIds = currentNoteIds

        // Whichever side of the split is empty gets unchecked.
        // Both have content (mid-line split): both stay checked.
        // Cursor at end: new line is empty → uncheck new line.
        // Cursor at start of content: current line is empty → uncheck current, keep new checked.
        if (cursor >= prefix.length) {
            val preserveChecked = afterHasContent
            createNewLineWithPrefix(lineIndex, afterCursor, prefix, newNoteIds, preserveChecked)
            if (!beforeHasContent && prefix.contains(LinePrefixes.CHECKBOX_CHECKED)) {
                val uncheckedText = beforeCursor.replace(LinePrefixes.CHECKBOX_CHECKED, LinePrefixes.CHECKBOX_UNCHECKED)
                line.updateFull(uncheckedText, uncheckedText.length)
            }
        } else {
            // Cursor within prefix, don't continue prefix
            val newLine = LineState(afterCursor, 0, newNoteIds)
            state.lines.add(lineIndex + 1, newLine)
            state.focusedLineIndex = lineIndex + 1
            state.requestFocusUpdate()
            state.notifyChange()
        }

        // Continue pending state on new line (groups Enter + subsequent typing)
        undoManager.continueAfterStructuralChange(state.focusedLineIndex)
    }

    /**
     * Deletes a line and transfers its noteIds to the surviving line.
     * Used by deleteBackward when one or both lines have no content.
     */
    private fun deleteLineAndMergeIds(
        survivor: LineState, other: LineState,
        removeIndex: Int, focusIndex: Int
    ) {
        undoManager.captureStateBeforeChange(state)
        state.clearSelection()
        val merged = mergeNoteIds(survivor, other)
        survivor.noteIds = merged.noteIds
        survivor.noteIdContentLengths = merged.contentLengths
        state.lines.removeAt(removeIndex)
        state.focusedLineIndex = focusIndex
        state.requestFocusUpdate()
        state.notifyChange()
        undoManager.beginEditingLine(state, state.focusedLineIndex)
    }

    /**
     * Merge current line with previous line.
     * Creates an undo boundary before the merge.
     */
    fun mergeToPreviousLine(lineIndex: Int, targetIndex: Int = lineIndex - 1) {
        if (targetIndex < 0) return

        // Always capture state before merge - line merge is always undoable
        undoManager.captureStateBeforeChange(state)

        state.clearSelection()
        val currentLine = state.lines.getOrNull(lineIndex) ?: return
        val previousLine = state.lines.getOrNull(targetIndex) ?: return

        val merged = mergeNoteIds(previousLine, currentLine)

        val previousLength = previousLine.text.length
        previousLine.updateFull(previousLine.text + currentLine.content, previousLength)
        previousLine.noteIds = merged.noteIds
        previousLine.noteIdContentLengths = merged.contentLengths
        state.lines.removeAt(lineIndex)
        state.focusedLineIndex = targetIndex
        state.requestFocusUpdate()
        state.notifyChange()

        // Begin editing the merged line
        undoManager.beginEditingLine(state, state.focusedLineIndex)
    }

    /**
     * Merge next line into current line.
     * Creates an undo boundary before the merge.
     */
    fun mergeNextLine(lineIndex: Int, targetIndex: Int = lineIndex + 1) {
        if (targetIndex >= state.lines.size) return

        // Always capture state before merge - line merge is always undoable
        undoManager.captureStateBeforeChange(state)

        state.clearSelection()
        val currentLine = state.lines.getOrNull(lineIndex) ?: return
        val nextLine = state.lines.getOrNull(targetIndex) ?: return

        val merged = mergeNoteIds(currentLine, nextLine)

        val currentLength = currentLine.text.length
        currentLine.updateFull(currentLine.text + nextLine.content, currentLength)
        currentLine.noteIds = merged.noteIds
        currentLine.noteIdContentLengths = merged.contentLengths
        state.lines.removeAt(targetIndex)
        state.requestFocusUpdate()
        state.notifyChange()

        // Begin editing the merged line
        undoManager.beginEditingLine(state, state.focusedLineIndex)
    }

    // =========================================================================
    // Cursor Operations
    // =========================================================================

    /**
     * Set cursor position within a line.
     * Properly handles undo state when focus changes.
     */
    fun setCursor(lineIndex: Int, position: Int) {
        val line = state.lines.getOrNull(lineIndex) ?: return
        // Handle undo state when changing lines
        if (lineIndex != state.focusedLineIndex) {
            undoManager.commitPendingUndoState(state)
            state.focusedLineIndex = lineIndex
            undoManager.beginEditingLine(state, lineIndex)
        } else {
            state.focusedLineIndex = lineIndex
        }
        line.updateFull(line.text, position.coerceIn(0, line.text.length))
        state.clearSelection()
        state.requestFocusUpdate()
    }

    /**
     * Set cursor position within a line's content (excluding prefix).
     * Use this when the position comes from content-space (e.g., tap on rendered text).
     */
    fun setContentCursor(lineIndex: Int, contentPosition: Int) {
        val line = state.lines.getOrNull(lineIndex) ?: return
        val fullPosition = line.prefix.length + contentPosition
        setCursor(lineIndex, fullPosition)
    }

    /**
     * Set cursor from global character offset.
     * Properly handles undo state when focus changes.
     */
    fun setCursorFromGlobalOffset(globalOffset: Int) {
        state.clearSelection()
        val (lineIndex, localOffset) = state.getLineAndLocalOffset(globalOffset)
        // Handle undo state when changing lines
        if (lineIndex != state.focusedLineIndex) {
            undoManager.commitPendingUndoState(state)
            state.focusedLineIndex = lineIndex
            undoManager.beginEditingLine(state, lineIndex)
        } else {
            state.focusedLineIndex = lineIndex
        }
        val line = state.lines.getOrNull(lineIndex) ?: return
        line.updateFull(line.text, localOffset)
        state.requestFocusUpdate()
    }

    // =========================================================================
    // Content Update (for composing text / IME sync)
    // =========================================================================

    /**
     * Update line content directly (used by IME for composing text).
     * This replaces the content portion while preserving the prefix.
     *
     * IMPORTANT: This is a direct content update from IME. Any selection should be
     * cleared first by the caller if needed. We don't try to be clever about
     * selection replacement here - that led to bugs.
     */
    fun updateLineContent(lineIndex: Int, newContent: String, contentCursor: Int) {
        val line = state.lines.getOrNull(lineIndex) ?: return

        // Check if this is a newline insertion
        if (newContent.contains('\n')) {
            // Prepare for structural change - commits prior typing, captures pre-split state
            undoManager.prepareForStructuralChange(state)

            state.clearSelection()
            val newlineIndex = newContent.indexOf('\n')
            val beforeNewline = newContent.substring(0, newlineIndex)
            val afterNewline = newContent.substring(newlineIndex + 1)

            val noteIds = line.noteIds
            val (currentNoteIds, newNoteIds) = org.alkaline.taskbrain.data.splitNoteIds(
                noteIds, beforeNewline.length, afterNewline.length,
                beforeNewline.isNotEmpty(), afterNewline.isNotEmpty(), line.noteIdContentLengths
            )

            // Update current line with content before newline
            line.updateContent(beforeNewline, beforeNewline.length)
            line.noteIds = currentNoteIds

            // Create new line with prefix continuation.
            // Preserve checked state when content follows the split point.
            val preserveChecked = afterNewline.isNotEmpty()
            createNewLineWithPrefix(lineIndex, afterNewline, line.prefix, newNoteIds, preserveChecked)

            // Continue pending state on new line (groups Enter + subsequent typing)
            undoManager.continueAfterStructuralChange(state.focusedLineIndex)
            return
        }

        // Check if content actually changed before updating
        val contentChanged = line.content != newContent

        // Only clear selection if content actually changed
        // This prevents IME sync (finishComposingText) from clearing gutter selections
        // The old selection-replacement logic was causing bugs (like "Jg" → "Hg")
        // because it tried to extract inserted text and could corrupt content
        if (contentChanged) {
            state.clearSelection()
        }

        // Convert source prefixes to display prefixes (e.g. "* " → "• ")
        // Only when the line doesn't already have a bullet/checkbox prefix
        val converted = convertSourcePrefix(newContent, line.prefix)
        if (converted != null) {
            // Use updateFull to avoid double-read of the prefix getter in updateContent
            // (the converted content starts with a display prefix, which would be counted
            // twice: once as existing prefix, once as part of new text)
            val newText = line.prefix + converted.text
            val newCursor = line.prefix.length + converted.cursor
            line.updateFull(newText, newCursor)
        } else {
            line.updateContent(newContent, contentCursor)
        }
        state.notifyChange()

        // Only mark content changed if it actually changed
        // This prevents false positives after undo (IME sync sends same content)
        // which would incorrectly set hasUncommittedChanges and clear redo stack
        if (contentChanged) {
            undoManager.markContentChanged()
        }
    }

    private data class ConvertedPrefix(val text: String, val cursor: Int)

    /**
     * Converts source prefixes typed by the user into display prefixes.
     * "* " → "• ", "[] " → "☐ ", "[x] " → "☑ "
     * All require a trailing space to trigger conversion.
     * Only converts when the line's existing prefix has no bullet/checkbox (just tabs or empty).
     * Returns the converted content with absolute content cursor, or null if no conversion.
     */
    private fun convertSourcePrefix(content: String, existingPrefix: String): ConvertedPrefix? {
        // Only convert if the existing prefix is tabs-only (no bullet/checkbox already present)
        if (existingPrefix.any { it != '\t' }) return null

        if (content.startsWith(LinePrefixes.ASTERISK_SPACE)) {
            val rest = content.substring(LinePrefixes.ASTERISK_SPACE.length)
            return ConvertedPrefix(LinePrefixes.BULLET + rest, LinePrefixes.BULLET.length + rest.length)
        }
        if (content.startsWith(LinePrefixes.BRACKETS_CHECKED + " ")) {
            val rest = content.substring(LinePrefixes.BRACKETS_CHECKED.length + 1)
            return ConvertedPrefix(LinePrefixes.CHECKBOX_CHECKED + rest, LinePrefixes.CHECKBOX_CHECKED.length + rest.length)
        }
        if (content.startsWith(LinePrefixes.BRACKETS_EMPTY + " ")) {
            val rest = content.substring(LinePrefixes.BRACKETS_EMPTY.length + 1)
            return ConvertedPrefix(LinePrefixes.CHECKBOX_UNCHECKED + rest, LinePrefixes.CHECKBOX_UNCHECKED.length + rest.length)
        }
        return null
    }

    // =========================================================================
    // Focus Management
    // =========================================================================

    /**
     * Set focus to a specific line.
     * Triggers undo boundary when focus changes to a different line.
     */
    fun focusLine(lineIndex: Int) {
        if (lineIndex in state.lines.indices) {
            if (lineIndex != state.focusedLineIndex) {
                undoManager.commitPendingUndoState(state)
                state.focusedLineIndex = lineIndex
                undoManager.beginEditingLine(state, lineIndex)
            } else {
                state.focusedLineIndex = lineIndex
            }
            state.requestFocusUpdate()
        }
    }

    // =========================================================================
    // Selection Operations
    // =========================================================================

    /**
     * Check if there's an active selection.
     */
    fun hasSelection(): Boolean = state.hasSelection

    /**
     * Set selection range using global character offsets.
     * If start == end, this clears selection and sets cursor.
     */
    fun setSelection(start: Int, end: Int) {
        if (start == end) {
            setCursorFromGlobalOffset(start)
        } else {
            state.setSelection(start, end)
        }
    }

    /**
     * Set selection within a line using local content positions.
     * Converts local positions to global offsets and sets selection.
     *
     * @param lineIndex The line containing the selection
     * @param contentStart Start position within content (0 = first char after prefix)
     * @param contentEnd End position within content
     */
    fun setSelectionInLine(lineIndex: Int, contentStart: Int, contentEnd: Int) {
        val line = state.lines.getOrNull(lineIndex) ?: return
        val lineStart = state.getLineStartOffset(lineIndex)
        val prefixLength = line.prefix.length
        val contentLength = line.content.length

        val globalStart = lineStart + prefixLength + contentStart.coerceIn(0, contentLength)
        val globalEnd = lineStart + prefixLength + contentEnd.coerceIn(0, contentLength)

        if (globalStart == globalEnd) {
            setCursorFromGlobalOffset(globalStart)
        } else {
            state.setSelection(globalStart, globalEnd)
        }
    }

    /**
     * Handles space key when there's a selection.
     * Single space indents, double-space (within 250ms) unindents.
     * Creates an undo point so the indent/unindent can be undone.
     *
     * @return true if the space was handled (there was a selection), false otherwise
     */
    fun handleSpaceWithSelection(): Boolean {
        if (!state.hasSelection) return false
        // Use indent command type for grouping - consecutive space presses are grouped
        undoManager.recordCommand(state, CommandType.INDENT)
        return state.handleSpaceWithSelectionInternal()
    }

    /**
     * Replace the current selection with text (no undo handling).
     * If there's no selection, this is a no-op.
     * INTERNAL: Use paste() for proper undo handling.
     */
    internal fun replaceSelectionNoUndo(text: String) {
        if (state.hasSelection) {
            state.replaceSelectionInternal(text)
        }
    }

    /**
     * Delete the current selection (no undo handling).
     * INTERNAL: Use deleteSelectionWithUndo() for proper undo handling.
     */
    internal fun deleteSelectionNoUndo() {
        if (state.hasSelection) {
            state.deleteSelectionInternal()
        }
    }

    // =========================================================================
    // Line Prefix Operations
    // =========================================================================

    /**
     * Toggle checkbox state on a specific line (checked ↔ unchecked).
     * Does not add or remove the checkbox, only toggles existing ones.
     * Creates an undo point so the toggle can be undone.
     * Uses same undo pattern as command bar checkbox for consistency.
     */
    /**
     * Updates recentlyCheckedIndices after a checkbox toggle.
     * If the line was unchecked and is now checked, adds it; otherwise removes it.
     */
    private fun trackRecentlyChecked(lineIndex: Int, wasUnchecked: Boolean) {
        val isNowChecked = state.lines.getOrNull(lineIndex)?.prefix
            ?.contains(LinePrefixes.CHECKBOX_CHECKED) == true
        if (wasUnchecked && isNowChecked) {
            recentlyCheckedIndices.add(lineIndex)
        } else {
            recentlyCheckedIndices.remove(lineIndex)
        }
    }

    fun toggleCheckboxOnLine(lineIndex: Int) {
        val line = state.lines.getOrNull(lineIndex) ?: return
        val wasUnchecked = line.prefix.contains(LinePrefixes.CHECKBOX_UNCHECKED)
        // Use same undo pattern as command bar checkbox toggle
        undoManager.recordCommand(state, CommandType.CHECKBOX)
        line.toggleCheckboxState()
        undoManager.commitAfterCommand(state, CommandType.CHECKBOX)
        state.requestFocusUpdate()
        state.notifyChange()
        trackRecentlyChecked(lineIndex, wasUnchecked)
    }

    // =========================================================================
    // Read Access
    // =========================================================================

    /**
     * Get the current text of a line.
     */
    fun getLineText(lineIndex: Int): String = state.lines.getOrNull(lineIndex)?.text ?: ""

    /**
     * Get the content (without prefix) of a line.
     */
    fun getLineContent(lineIndex: Int): String = state.lines.getOrNull(lineIndex)?.content ?: ""

    /**
     * Get the cursor position within a line's content.
     */
    fun getContentCursor(lineIndex: Int): Int = state.lines.getOrNull(lineIndex)?.contentCursorPosition ?: 0

    /**
     * Get the full cursor position within a line.
     */
    fun getLineCursor(lineIndex: Int): Int = state.lines.getOrNull(lineIndex)?.cursorPosition ?: 0

    /**
     * Check if a line index is valid.
     */
    fun isValidLine(lineIndex: Int): Boolean = lineIndex in state.lines.indices
}

/**
 * Remember an EditorController for the given EditorState.
 */
@Composable
fun rememberEditorController(state: EditorState): EditorController {
    return remember(state) { EditorController(state) }
}
