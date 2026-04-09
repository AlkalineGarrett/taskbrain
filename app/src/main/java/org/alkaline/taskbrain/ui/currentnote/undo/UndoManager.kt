package org.alkaline.taskbrain.ui.currentnote.undo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.alkaline.taskbrain.ui.currentnote.EditorState

/**
 * Manages undo/redo history for the editor with line-level granularity.
 *
 * Undo boundaries are created when focus leaves a line. This groups all edits
 * to a single line into one undo step. Command bar buttons have special grouping:
 * - Bullet/Checkbox: Each press is a separate undo step
 * - Indent/Unindent: Consecutive presses are grouped into one undo step
 *
 * Also tracks alarm creation for undo/redo - undoing deletes the alarm permanently,
 * and redoing recreates it with the same configuration (but a new ID).
 */
class UndoManager(private val maxHistorySize: Int = 50) {
    private val undoStack = mutableListOf<UndoSnapshot>()
    private val redoStack = mutableListOf<UndoSnapshot>()

    // Baseline snapshot - the "floor" state that can't be undone past.
    // Set when a note is first loaded to prevent accidentally undoing to empty/wrong state.
    private var baselineSnapshot: UndoSnapshot? = null

    // Tracks whether we've undone all the way to the baseline.
    // When true, canUndo returns false (nothing left to undo).
    // Reset when new edits are made.
    private var isAtBaseline: Boolean = false

    // Pending state captured when editing starts on a line
    private var pendingSnapshot: UndoSnapshot? = null
    private var editingLineIndex: Int? = null

    // Last command type for grouping indent/unindent
    private var lastCommandType: CommandType? = null

    // Tracks the line range after the last move operation (for grouping consecutive moves)
    private var lastMoveLineRange: IntRange? = null

    // Tracks whether there are uncommitted edits (content changed since pendingSnapshot was captured)
    // This allows canUndo to return true as soon as the user starts typing, even before
    // the edits are committed to the undo stack (which happens on focus change).
    private var hasUncommittedChanges: Boolean = false

    /** Whether this editor has uncommitted changes. Exposed for UnifiedUndoManager. */
    val currentHasUncommittedChanges: Boolean get() = hasUncommittedChanges

    /** Callback fired when a new undo entry is committed (not during undo/redo operations). */
    var onEntryPushed: (() -> Unit)? = null

    /** Callback fired when the redo stack is cleared (not during undo/redo operations). */
    var onRedoCleared: (() -> Unit)? = null

    /** When true, suppresses onEntryPushed/onRedoCleared callbacks.
     *  Set by undo()/redo() to prevent callback feedback loops. */
    private var suppressCallbacks = false

    // Version number for reactivity - incremented when undo/redo state changes
    // UI components should read this to trigger recomposition when undo/redo availability changes
    // Uses Compose mutableStateOf so that reading this value in a composable creates a reactive subscription
    var stateVersion by mutableIntStateOf(0)
        private set

    private fun notifyStateChanged() {
        stateVersion++
    }

    /**
     * Marks that content has changed while editing. Call this when content is modified
     * (e.g., typing, deletion) so that canUndo reflects the uncommitted changes.
     * Also clears the redo stack since new edits invalidate the redo history.
     */
    fun markContentChanged() {
        if (pendingSnapshot != null && !hasUncommittedChanges) {
            hasUncommittedChanges = true
            clearRedoStack()
            notifyStateChanged()
        }
    }

    val canUndo: Boolean get() =
        undoStack.isNotEmpty() || hasUncommittedChanges || (baselineSnapshot != null && !isAtBaseline)
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val hasBaseline: Boolean get() = baselineSnapshot != null

    /**
     * Creates a snapshot of the current editor state.
     */
    private fun captureSnapshot(state: EditorState): UndoSnapshot {
        val focusedLine = state.lines.getOrNull(state.focusedLineIndex)
        return UndoSnapshot(
            lineContents = state.lines.map { it.text },
            focusedLineIndex = state.focusedLineIndex,
            cursorPosition = focusedLine?.cursorPosition ?: 0,
            lineNoteIds = state.lines.map { it.noteIds }
        )
    }

    /**
     * Sets the baseline snapshot - the "floor" state that can't be undone past.
     * Call this when a note is first loaded to ensure the user can always get back
     * to the loaded state, even if undo history is corrupted or empty.
     *
     * Also sets isAtBaseline = true because we're starting at the baseline state
     * with nothing to undo yet. This ensures canUndo returns false until the user
     * makes an edit.
     */
    fun setBaseline(state: EditorState) {
        baselineSnapshot = captureSnapshot(state)
        isAtBaseline = true  // We're starting at baseline, nothing to undo
        notifyStateChanged()
    }

    /**
     * Called when user starts editing a line (or focus changes to it).
     * Captures the state before editing begins.
     */
    fun beginEditingLine(state: EditorState, lineIndex: Int) {
        if (editingLineIndex != lineIndex) {
            pendingSnapshot = captureSnapshot(state)
            editingLineIndex = lineIndex
        }
        // Reset command type and move tracking when starting to edit a new line
        lastCommandType = null
        lastMoveLineRange = null
    }

    /**
     * Called when focus leaves the line - commits pending snapshot if content changed.
     * Clears redo stack when new edits are committed (standard text editor behavior).
     */
    fun commitPendingUndoState(currentState: EditorState) {
        val pending = pendingSnapshot ?: return
        val currentSnapshot = captureSnapshot(currentState)

        // Only commit if content actually changed
        if (pending.lineContents != currentSnapshot.lineContents) {
            pushUndo(pending)
            clearRedoStack()
        }

        // Clear pending state
        pendingSnapshot = null
        editingLineIndex = null
        lastCommandType = null
        lastMoveLineRange = null
        hasUncommittedChanges = false
    }

    /**
     * Prepares for a structural change (like Enter/split, line merge).
     *
     * This commits any pending typing edits and captures the current state
     * as the new pending snapshot. The structural change + any subsequent
     * typing will be grouped together as one undo step.
     *
     * Call this BEFORE performing the structural change.
     * The returned snapshot is the state to restore if undoing this change.
     */
    fun prepareForStructuralChange(currentState: EditorState) {
        val pending = pendingSnapshot
        val currentSnapshot = captureSnapshot(currentState)

        if (pending != null && pending.lineContents != currentSnapshot.lineContents) {
            // Content changed since editing began - commit that as a separate undo step
            pushUndo(pending)
            clearRedoStack()
        }

        // Set current state as the new pending snapshot
        // This becomes the undo point for the structural change + subsequent typing
        pendingSnapshot = currentSnapshot
        editingLineIndex = currentState.focusedLineIndex
        lastCommandType = null
        hasUncommittedChanges = false  // New pending snapshot, no uncommitted changes yet
    }

    /**
     * Called after a structural change to continue the pending state on the new line.
     * This keeps the pre-change snapshot as pending so the change + subsequent
     * typing are grouped together.
     */
    fun continueAfterStructuralChange(newLineIndex: Int) {
        // Keep the pending snapshot (captured before the change)
        // Just update the editing line index to the new line
        editingLineIndex = newLineIndex
    }

    /**
     * Captures the current state as an immediate undo point.
     * Use for operations like line merge that should always be undoable
     * regardless of whether there were preceding edits.
     *
     * Unlike prepareForStructuralChange, this does NOT set up pending state -
     * the caller should call beginEditingLine after the change completes.
     */
    fun captureStateBeforeChange(currentState: EditorState) {
        // Commit any pending edits first (may create one undo point)
        commitPendingUndoState(currentState)
        // Capture current state and push to undo stack (creates another undo point)
        pushUndo(captureSnapshot(currentState))
        clearRedoStack()
    }

    /**
     * Records command type for grouping command bar actions.
     * Returns true if an undo state should be committed before this command.
     */
    fun recordCommand(state: EditorState, type: CommandType): Boolean {
        val shouldCommit = when (type) {
            CommandType.BULLET, CommandType.CHECKBOX -> {
                // Always commit before and after bullet/checkbox
                true
            }
            CommandType.INDENT, CommandType.UNINDENT -> {
                // Only commit if last command was NOT indent/unindent
                lastCommandType !in listOf(CommandType.INDENT, CommandType.UNINDENT)
            }
            CommandType.MOVE -> {
                // Move uses recordMoveCommand instead - shouldn't reach here
                true
            }
            CommandType.OTHER -> {
                true
            }
        }

        if (shouldCommit) {
            commitPendingUndoState(state)
            pendingSnapshot = captureSnapshot(state)
            editingLineIndex = state.focusedLineIndex
        }

        lastCommandType = type
        return shouldCommit
    }

    /**
     * Commits the current state after a command (for bullet/checkbox).
     * Clears redo stack when new edits are committed.
     */
    fun commitAfterCommand(state: EditorState, type: CommandType) {
        if (type in listOf(CommandType.BULLET, CommandType.CHECKBOX)) {
            val pending = pendingSnapshot
            if (pending != null) {
                pushUndo(pending)
                clearRedoStack()
                pendingSnapshot = null
                editingLineIndex = null
            }
        }
    }

    /**
     * Records a move command for undo grouping.
     * Consecutive moves of the same line range are grouped into one undo step.
     * Returns true if a new undo boundary was created (not grouped).
     */
    fun recordMoveCommand(state: EditorState, newRange: IntRange): Boolean {
        val isSameGroup = lastCommandType == CommandType.MOVE &&
                          lastMoveLineRange == newRange

        if (!isSameGroup) {
            commitPendingUndoState(state)
            pendingSnapshot = captureSnapshot(state)
            editingLineIndex = state.focusedLineIndex
        }

        lastMoveLineRange = newRange
        lastCommandType = CommandType.MOVE
        return !isSameGroup
    }

    /**
     * Updates the tracked move range after a move operation completes.
     * Call this after the move is performed with the new line range.
     */
    fun updateMoveRange(newRange: IntRange) {
        lastMoveLineRange = newRange
    }

    /**
     * Records that an alarm was just created.
     * Attaches alarm data to the most recent undo entry.
     * If no undo entry exists, creates one from the pending snapshot to ensure
     * the alarm creation can be undone.
     */
    fun recordAlarmCreation(alarm: AlarmSnapshot, currentState: EditorState) {
        // If undo stack is empty but we have a pending snapshot, commit it first
        // to ensure there's an undo entry to attach the alarm to
        if (undoStack.isEmpty() && pendingSnapshot != null) {
            pushUndo(pendingSnapshot!!)
            clearRedoStack()
            pendingSnapshot = null
            editingLineIndex = null
        }

        if (undoStack.isNotEmpty()) {
            val lastEntry = undoStack.last()
            undoStack[undoStack.lastIndex] = lastEntry.copy(createdAlarm = alarm)
        }
    }

    /**
     * Performs undo - returns the snapshot to restore.
     * Caller is responsible for handling alarm deletion if snapshot has createdAlarm.
     * If the undo stack is empty but a baseline exists, returns the baseline (floor state).
     * Returns null if already at baseline (nothing to undo).
     */
    fun undo(currentState: EditorState): UndoSnapshot? {
        suppressCallbacks = true
        try {
            // Commit any pending edits first
            commitPendingUndoState(currentState)

            // Check if we have anything to undo to
            if (undoStack.isEmpty() && (baselineSnapshot == null || isAtBaseline)) return null

            // Determine the target snapshot
            val targetSnapshot = if (undoStack.isNotEmpty()) {
                val snapshot = undoStack.removeAt(undoStack.lastIndex)
                // If stack is now empty, we've reached the baseline state
                if (undoStack.isEmpty() && baselineSnapshot != null) {
                    isAtBaseline = true
                }
                snapshot
            } else {
                // Use baseline as floor - mark that we're now at baseline
                isAtBaseline = true
                baselineSnapshot!!
            }

            // Only push to redo stack if current state differs from target (avoid no-op redos)
            val currentSnapshot = captureSnapshot(currentState)
            if (currentSnapshot.lineContents != targetSnapshot.lineContents) {
                redoStack.add(currentSnapshot)
            }
            notifyStateChanged()

            return targetSnapshot
        } finally {
            suppressCallbacks = false
        }
    }

    /**
     * Performs redo - returns the snapshot to restore.
     * Caller is responsible for handling alarm recreation if snapshot has createdAlarm.
     */
    fun redo(currentState: EditorState): UndoSnapshot? {
        suppressCallbacks = true
        try {
            if (redoStack.isEmpty()) return null

            // Save current state to undo stack (without alarm - it was the state before the original action)
            val currentSnapshot = captureSnapshot(currentState)
            pushUndo(currentSnapshot)

            // Pop and return the state to restore
            val snapshot = redoStack.removeAt(redoStack.lastIndex)
            notifyStateChanged()
            return snapshot
        } finally {
            suppressCallbacks = false
        }
    }

    /**
     * Updates the alarm ID in the most recent undo entry.
     * Called after redo recreates an alarm with a new ID.
     */
    fun updateLastUndoAlarmId(newId: String) {
        if (undoStack.isNotEmpty()) {
            val lastEntry = undoStack.last()
            lastEntry.createdAlarm?.let { alarm ->
                undoStack[undoStack.lastIndex] = lastEntry.copy(
                    createdAlarm = alarm.copy(id = newId)
                )
            }
        }
    }

    private fun pushUndo(snapshot: UndoSnapshot) {
        undoStack.add(snapshot)
        // New undo entry means we're no longer at baseline
        isAtBaseline = false
        // Limit history size
        while (undoStack.size > maxHistorySize) {
            undoStack.removeAt(0)
        }
        notifyStateChanged()
        // Note: Redo stack clearing is handled by callers, not here,
        // because redo() also uses pushUndo but should not clear redo.
        if (!suppressCallbacks) onEntryPushed?.invoke()
    }

    /**
     * Clears redo stack - call when user takes a new action after undo.
     */
    fun clearRedoStack() {
        if (redoStack.isNotEmpty()) {
            redoStack.clear()
            notifyStateChanged()
            if (!suppressCallbacks) onRedoCleared?.invoke()
        }
    }

    /**
     * Resets all history. Call when loading a new note.
     * Note: This also clears the baseline. Call setBaseline() after loading
     * the note content to establish a new floor.
     */
    fun reset() {
        undoStack.clear()
        redoStack.clear()
        baselineSnapshot = null
        isAtBaseline = false
        pendingSnapshot = null
        editingLineIndex = null
        lastCommandType = null
        lastMoveLineRange = null
        hasUncommittedChanges = false
        notifyStateChanged()
    }

    /**
     * Exports the current state for persistence.
     */
    fun exportState(): UndoManagerState {
        return UndoManagerState(
            undoStack = undoStack.toList(),
            redoStack = redoStack.toList(),
            baselineSnapshot = baselineSnapshot,
            isAtBaseline = isAtBaseline,
            pendingSnapshot = pendingSnapshot,
            editingLineIndex = editingLineIndex,
            lastCommandType = lastCommandType,
            lastMoveLineRange = lastMoveLineRange,
            hasUncommittedChanges = hasUncommittedChanges
        )
    }

    /**
     * Imports state from persistence.
     */
    fun importState(state: UndoManagerState) {
        undoStack.clear()
        undoStack.addAll(state.undoStack)
        redoStack.clear()
        redoStack.addAll(state.redoStack)
        baselineSnapshot = state.baselineSnapshot
        isAtBaseline = state.isAtBaseline
        pendingSnapshot = state.pendingSnapshot
        editingLineIndex = state.editingLineIndex
        lastCommandType = state.lastCommandType
        lastMoveLineRange = state.lastMoveLineRange
        hasUncommittedChanges = state.hasUncommittedChanges
        notifyStateChanged()
    }
}
