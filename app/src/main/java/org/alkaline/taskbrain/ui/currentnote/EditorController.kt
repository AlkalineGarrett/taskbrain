package org.alkaline.taskbrain.ui.currentnote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import org.alkaline.taskbrain.data.DeletionSource
import org.alkaline.taskbrain.data.NoteIdSentinel
import org.alkaline.taskbrain.data.NoteStore
import org.alkaline.taskbrain.ui.currentnote.undo.CommandType
import org.alkaline.taskbrain.ui.currentnote.undo.UndoManager
import org.alkaline.taskbrain.ui.currentnote.undo.AlarmSnapshot
import org.alkaline.taskbrain.ui.currentnote.undo.UndoSnapshot
import org.alkaline.taskbrain.ui.currentnote.selection.SelectionCoordinates
import org.alkaline.taskbrain.ui.currentnote.util.ClipboardParser
import org.alkaline.taskbrain.ui.currentnote.util.PasteResult
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

    /**
     * Walks from [fromIndex] in [direction] (-1 = up, 1 = down), skipping any
     * lines in [hiddenIndices]. Returns the first visible line index, or null
     * if the walk runs off the edge. Callers typically pass `lineIndex ± 1` to
     * find the visible neighbor of a given line.
     */
    fun findVisibleNeighbor(fromIndex: Int, direction: Int): Int? {
        val max = state.lines.size
        var target = fromIndex
        while (target in 0 until max) {
            if (target !in hiddenIndices) return target
            target += direction
        }
        return null
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
        // Pre-record selection lines so PasteHandler's "replace the selection"
        // semantics tag any genuinely-removed ids. Lines that the new paste
        // re-claims (same id reappears) survive — the stale tag is harmless.
        if (state.hasSelection) {
            recordRemovalForRange(state.getSelectedLineRange(), DeletionSource.PASTE_REPLACE)
        }
        val result = PasteHandler.execute(
            state.lines.toList(),
            state.focusedLineIndex,
            state.selection,
            parsed,
            cutLines.takeIf { it.isNotEmpty() },
        )
        // The sharedCutLines path inside PasteHandler recovered each cut
        // line's real noteId already — drop those ids from the cross-save
        // buffer so a second paste of the same content doesn't double-claim.
        for (cl in cutLines) {
            val id = cl.noteIds.firstOrNull()
            if (id != null && NoteIdSentinel.isRealNoteId(id)) NoteStore.clearPendingCut(id)
        }
        // Cross-save reclaim: a pasted line whose head is a sentinel (new-line
        // marker) didn't recover from this session's sharedCutLines. Try the
        // NoteStore cut buffer — content match recovers cuts parked as
        // cut-delete by an intervening save.
        for (line in result.lines) {
            val head = line.noteIds.firstOrNull()
            if (head != null && NoteIdSentinel.isSentinel(head)) {
                val stripped = line.text.trimStart('\t')
                val reclaimed = NoteStore.tryReclaim(stripped)
                if (reclaimed != null) line.noteIds = listOf(reclaimed)
            }
        }
        applyPasteResult(result)
    }

    /**
     * Replace `state.lines` wholesale without ever passing through an
     * empty intermediate state. The naive `clear() + addAll(...)` makes
     * `state.lines` momentarily empty, which Compose may snapshot — and
     * any observer that fires during that window (a lifecycle save
     * triggered by a phantom activity-stop, the save button, an external
     * change handler) sees a degenerate editor. The save planner then
     * thinks every existing child should be soft-deleted, and only the
     * content-drop guard prevents data loss.
     *
     * Wrapping in [Snapshot.withMutableSnapshot] commits both ops as a
     * single atomic transaction visible to readers.
     */
    private fun replaceAllLinesAtomically(newLines: List<LineState>) {
        Snapshot.withMutableSnapshot {
            state.lines.clear()
            state.lines.addAll(newLines)
        }
    }

    /**
     * Apply a [PasteResult] to the editor state — replace lines, place
     * the cursor, clear selection, and notify. Shared by [paste] and
     * [moveSelectionTo] so both apply pastes identically.
     */
    private fun applyPasteResult(result: PasteResult) {
        replaceAllLinesAtomically(result.lines)
        state.focusedLineIndex = result.cursorLineIndex
        state.lines.getOrNull(result.cursorLineIndex)?.updateFull(
            state.lines[result.cursorLineIndex].text,
            result.cursorPosition,
        )
        state.clearSelection()
        state.requestFocusUpdate()
        state.notifyChange()
    }

    // =========================================================================
    // Drag-move callbacks (called by the gesture handler)
    // =========================================================================

    /** Update the drop-cursor position to track the user's finger. */
    fun onMoveDragUpdate(globalOffset: Int) {
        val (idx, localOffset) = state.getLineAndLocalOffset(globalOffset)
        state.dropCursorLineIndex = idx
        state.dropCursorLocalOffset = localOffset
    }

    /** Finger released over a valid drop target — clear cursor, perform the move. */
    fun onMoveDragCommit(globalOffset: Int) {
        state.dropCursorLineIndex = null
        moveSelectionTo(globalOffset)
    }

    /** Gesture interrupted (cancellation, scroll takeover, key change) — clear cursor. */
    fun cancelMoveDrag() {
        state.dropCursorLineIndex = null
    }

    // =========================================================================
    // Deletion-source tracking
    // =========================================================================

    /**
     * Per-line deletion source for the next save. Every line-removal site
     * in the editor records its source here before mutating state; the
     * save layer reads this map to stamp source-tagged `deletionBatchId`s
     * onto the docs in its `toDelete` set.
     *
     * Stale entries (a tagged id that ends up surviving — e.g. a moved
     * selection that re-attaches at the drop target) are harmless: the
     * save only consults the map for ids it's already decided to delete.
     */
    private val pendingSoftDeletes = mutableMapOf<String, DeletionSource>()

    /** Consume and clear the tracker. Called by the ViewModel at save time. */
    fun consumePendingSoftDeletes(): Map<String, DeletionSource> {
        val out = pendingSoftDeletes.toMap()
        pendingSoftDeletes.clear()
        return out
    }

    /** Discard any tracked entries — used when the editor is re-initialized for a new note. */
    fun resetPendingSoftDeletes() {
        pendingSoftDeletes.clear()
    }

    private fun recordRemoval(noteIds: Iterable<String>, source: DeletionSource) {
        for (id in noteIds) {
            if (NoteIdSentinel.isRealNoteId(id)) pendingSoftDeletes[id] = source
        }
    }

    private fun recordRemovalForRange(range: IntRange, source: DeletionSource) {
        val last = range.last.coerceAtMost(state.lines.lastIndex)
        if (range.first < 0 || last < range.first) return
        for (i in range.first..last) recordRemoval(state.lines[i].noteIds, source)
    }

    /**
     * Move the current selection to [targetGlobalOffset] (drop position).
     * Mirrors `EditorController.moveSelectionTo` on web.
     *
     * No-op if there's no selection or the target is inside the selection.
     * Otherwise: snapshot the selected lines (with noteIds), delete them,
     * and re-paste at the adjusted target. The snapshots feed into
     * [PasteHandler.execute]'s `cutLines` so the moved lines retain their
     * Firestore doc ids.
     *
     * Two safety nets:
     * - The whole op is wrapped in [Snapshot.withMutableSnapshot] so
     *   Compose observers can't see a half-mutated editor between
     *   delete-selection and apply-paste.
     * - A pre/post invariant check logs (does NOT throw) if any real
     *   Firestore doc id was lost across the op. The save planner's
     *   content-drop guard is the production backstop;
     *   `MoveSelectionToTest` enforces the invariant in CI.
     */
    fun moveSelectionTo(targetGlobalOffset: Int) {
        if (!state.hasSelection) return
        val (selStart, selEnd) = SelectionCoordinates.getEffectiveSelectionRange(
            state.text, state.selection,
        )
        if (targetGlobalOffset in selStart..selEnd) return

        val realIdsBefore = state.lines
            .flatMap { it.noteIds }
            .filter { NoteIdSentinel.isRealNoteId(it) }
            .toSet()

        executeOperation(OperationType.PASTE) {
            // Without this outer snapshot, an observer that runs between
            // delete-selection and apply-paste sees a half-modified editor.
            // A lifecycle ON_STOP save firing in that window would persist
            // the half-state (e.g., post-delete + pre-paste = 1 surviving
            // line over a multi-child note → content-drop guard trips).
            Snapshot.withMutableSnapshot {
                val range = state.getSelectedLineRange()
                // Most cuts re-attach via PasteHandler.execute's reuse, so they
                // won't end up in toDelete; tagging them is a safety net for
                // edge cases where a moved id ends up genuinely removed.
                recordRemovalForRange(range, DeletionSource.MOVE)
                val cutLines = state.lines.subList(range.first, range.last + 1)
                    .map { LineState(it.text, noteIds = it.noteIds.toList()) }
                for (line in cutLines) {
                    val id = line.noteIds.firstOrNull()
                    if (id != null && NoteIdSentinel.isRealNoteId(id)) {
                        NoteStore.recordCut(id, line.text.trimStart('\t'))
                    }
                }

                val clipText = getSelectedTextWithPrefix()
                if (clipText.isEmpty()) return@withMutableSnapshot

                state.deleteSelectionInternal()

                val adjustedTarget = if (targetGlobalOffset > selEnd) {
                    targetGlobalOffset - (selEnd - selStart)
                } else {
                    targetGlobalOffset
                }
                val (lineIndex, localOffset) = state.getLineAndLocalOffset(adjustedTarget)
                state.focusedLineIndex = lineIndex
                state.lines.getOrNull(lineIndex)?.let { line ->
                    line.updateFull(line.text, localOffset)
                }

                val parsed = ClipboardParser.parse(clipText, null)
                val result = PasteHandler.execute(
                    state.lines.toList(),
                    state.focusedLineIndex,
                    state.selection,
                    parsed,
                    cutLines.takeIf { it.isNotEmpty() },
                )
                for (cl in cutLines) {
                    val id = cl.noteIds.firstOrNull()
                    if (id != null && NoteIdSentinel.isRealNoteId(id)) NoteStore.clearPendingCut(id)
                }
                applyPasteResult(result)
            }
        }

        val realIdsAfter = state.lines
            .flatMap { it.noteIds }
            .filter { NoteIdSentinel.isRealNoteId(it) }
            .toSet()
        val lost = realIdsBefore - realIdsAfter
        if (lost.isNotEmpty()) {
            android.util.Log.e(
                "EditorController",
                "moveSelectionTo lost ${lost.size} real noteId(s): $lost. " +
                    "Editor's lines now: ${state.lines.size}. " +
                    "Selection moved [$selStart..$selEnd] to $targetGlobalOffset."
            )
        }
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

            // Feed every cut line with a real noteId into the cross-save
            // reclaim buffer. Source-note save will skip soft-delete; if a
            // paste in this session matches by content, the line keeps its
            // original noteId. Unmatched cuts get cut-delete on save so
            // they're parked, not orphaned. Content key is tab-stripped so
            // paste at any indent level can match.
            for (line in sharedCutLines) {
                val id = line.noteIds.firstOrNull()
                if (id != null && NoteIdSentinel.isRealNoteId(id)) {
                    NoteStore.recordCut(id, line.text.trimStart('\t'))
                }
            }

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
            recordRemovalForRange(state.getSelectedLineRange(), DeletionSource.SELECTION_DELETE)
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
    fun moveUp(): Boolean = performMove(moveUp = true)

    /**
     * Moves the current line/selection down.
     * Consecutive moves of the same lines are grouped into one undo step.
     * @return true if the move was performed, false if at boundary
     */
    fun moveDown(): Boolean = performMove(moveUp = false)

    private fun performMove(moveUp: Boolean): Boolean {
        val range = if (state.hasSelection) state.getSelectedLineRange() else state.getLogicalBlock(state.focusedLineIndex)
        val target = state.getMoveTarget(moveUp = moveUp, hiddenIndices = hiddenIndices) ?: return false

        // Lay down a gutter-style selection over the moved range before moving:
        // Compose disposes the focused textfield during the reorder, so the
        // text cursor disappears. The blue line-selection highlight is the
        // visual anchor for "this is what just moved." moveLinesInternal
        // updates the selection to follow the new line positions.
        state.selectLineRange(range)

        // Record for undo grouping (uses range BEFORE move for grouping check)
        undoManager.recordMoveCommand(state, range)

        val newRange = state.moveLinesInternal(range, target) ?: return false
        undoManager.updateMoveRange(newRange)
        undoManager.markContentChanged()
        state.requestScrollIntoView()
        return true
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
            recordRemovalForRange(state.getSelectedLineRange(), DeletionSource.SELECTION_DELETE)
            state.deleteSelectionInternal()
            undoManager.markContentChanged()
            return
        }

        val line = state.lines.getOrNull(lineIndex) ?: return
        val cursor = line.cursorPosition

        // At start of line content (at or before prefix end)
        if (cursor <= line.prefix.length) {
            val target = findVisibleNeighbor(lineIndex - 1, -1) ?: return
            val previousLine = state.lines.getOrNull(target) ?: return
            val currentHasContent = line.content.isNotEmpty()
            val previousHasContent = previousLine.content.isNotEmpty()

            when {
                // Previous has content: merge current's content onto previous
                previousHasContent -> {
                    recordRemoval(line.noteIds, DeletionSource.BACKSPACE_MERGE)
                    mergeToPreviousLine(lineIndex, target)
                }
                // Previous empty, current has content: delete the empty previous line
                currentHasContent -> {
                    recordRemoval(previousLine.noteIds, DeletionSource.BACKSPACE_MERGE)
                    deleteLineAndMergeIds(
                        survivor = line, other = previousLine,
                        removeIndex = target, focusIndex = target
                    )
                }
                // Neither has content: keep previous, delete current
                else -> {
                    recordRemoval(line.noteIds, DeletionSource.BACKSPACE_MERGE)
                    deleteLineAndMergeIds(
                        survivor = previousLine, other = line,
                        removeIndex = lineIndex, focusIndex = target
                    )
                }
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
            recordRemovalForRange(state.getSelectedLineRange(), DeletionSource.SELECTION_DELETE)
            state.deleteSelectionInternal()
            undoManager.markContentChanged()
            return
        }

        val line = state.lines.getOrNull(lineIndex) ?: return
        val cursor = line.cursorPosition

        // At end of line - merge with next visible line (creates its own undo boundary)
        if (cursor >= line.text.length) {
            val target = findVisibleNeighbor(lineIndex + 1, 1) ?: return
            val nextLine = state.lines.getOrNull(target) ?: return

            val currentHasContent = line.content.isNotEmpty()
            val nextHasContent = nextLine.content.isNotEmpty()

            when {
                currentHasContent -> {
                    recordRemoval(nextLine.noteIds, DeletionSource.DELETE_MERGE)
                    mergeNextLine(lineIndex, target)
                }
                nextHasContent -> {
                    recordRemoval(line.noteIds, DeletionSource.DELETE_MERGE)
                    deleteLineAndMergeIds(
                        survivor = nextLine, other = line,
                        removeIndex = lineIndex, focusIndex = lineIndex
                    )
                }
                else -> {
                    recordRemoval(nextLine.noteIds, DeletionSource.DELETE_MERGE)
                    deleteLineAndMergeIds(
                        survivor = line, other = nextLine,
                        removeIndex = target, focusIndex = lineIndex
                    )
                }
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

        if (cursor < prefix.length) {
            // Cursor inside prefix: text-level split with no prefix continuation.
            val beforeText = line.text.substring(0, cursor)
            val afterText = line.text.substring(cursor)
            val (currentNoteIds, newNoteIds) = org.alkaline.taskbrain.data.splitNoteIds(
                line.noteIds, 0, afterText.length, false, afterText.isNotEmpty(),
                line.noteIdContentLengths,
            )
            line.updateFull(beforeText, beforeText.length)
            line.noteIds = currentNoteIds
            state.lines.add(lineIndex + 1, LineState(afterText, 0, newNoteIds))
            state.focusedLineIndex = lineIndex + 1
            state.requestFocusUpdate()
            state.notifyChange()
        } else {
            val beforeContent = line.text.substring(prefix.length, cursor)
            val afterContent = line.text.substring(cursor)
            // Whichever side of the split is empty gets unchecked.
            // Mid-line split: both stay checked.
            // Cursor at end: new line is empty → uncheck new line.
            // Cursor at start of content: current line is empty → uncheck current, keep new checked.
            splitLineByContent(lineIndex, line, beforeContent, afterContent, preserveCheckedAfter = afterContent.isNotEmpty())
            if (beforeContent.isEmpty() && prefix.contains(LinePrefixes.CHECKBOX_CHECKED)) {
                val uncheckedText = line.text.replace(LinePrefixes.CHECKBOX_CHECKED, LinePrefixes.CHECKBOX_UNCHECKED)
                line.updateFull(uncheckedText, uncheckedText.length)
            }
        }

        // Continue pending state on new line (groups Enter + subsequent typing)
        undoManager.continueAfterStructuralChange(state.focusedLineIndex)
    }

    /**
     * Splits a line at a content boundary: writes [beforeContent] to the existing line
     * (preserving prefix), inserts a new line below with [afterContent] and a continued
     * prefix (per [preserveCheckedAfter]), and distributes noteIds across the split.
     * splitNoteIds stamps a sentinel on any side without an id — SPLIT for
     * content-bearing halves, TYPED for fresh empty halves (Enter at edge).
     *
     * Caller must wrap with prepareForStructuralChange / continueAfterStructuralChange
     * + clearSelection.
     */
    private fun splitLineByContent(
        lineIndex: Int,
        line: LineState,
        beforeContent: String,
        afterContent: String,
        preserveCheckedAfter: Boolean,
    ) {
        val (currentNoteIds, newNoteIds) = org.alkaline.taskbrain.data.splitNoteIds(
            line.noteIds,
            beforeContent.length, afterContent.length,
            beforeContent.isNotEmpty(), afterContent.isNotEmpty(),
            line.noteIdContentLengths,
        )
        line.updateContent(beforeContent, beforeContent.length)
        line.noteIds = currentNoteIds
        createNewLineWithPrefix(lineIndex, afterContent, line.prefix, newNoteIds, preserveCheckedAfter)
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
     * True if a tap at [contentPosition] within line [lineIndex] would land inside
     * the active selection.
     */
    fun isContentOffsetInSelection(lineIndex: Int, contentPosition: Int): Boolean {
        if (!state.hasSelection) return false
        val line = state.lines.getOrNull(lineIndex) ?: return false
        val globalOffset = state.getLineStartOffset(lineIndex) + line.prefix.length + contentPosition
        return globalOffset in state.selection.min..state.selection.max
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

        if (newContent.contains('\n')) {
            splitLineOnNewline(lineIndex, line, newContent)
            return
        }

        if (line.content == newContent) {
            // No-op IME sync (e.g. finishComposingText echoing same text): update cursor
            // only — skip selection clear (would wipe gutter selections), prefix conversion,
            // sentinel stamp, and markContentChanged (would clobber redo after undo).
            line.updateContent(newContent, contentCursor)
            state.notifyChange()
            return
        }

        state.clearSelection()
        applyContent(line, newContent, contentCursor)
        stampNoteIdSentinelIfNeeded(lineIndex, line)
        state.notifyChange()
        undoManager.markContentChanged()
    }

    private fun splitLineOnNewline(lineIndex: Int, line: LineState, newContent: String) {
        // prepareForStructuralChange commits prior typing and captures pre-split state.
        undoManager.prepareForStructuralChange(state)
        state.clearSelection()

        val newlineIndex = newContent.indexOf('\n')
        val beforeNewline = newContent.substring(0, newlineIndex)
        val afterNewline = newContent.substring(newlineIndex + 1)
        splitLineByContent(lineIndex, line, beforeNewline, afterNewline, preserveCheckedAfter = afterNewline.isNotEmpty())

        // Continue pending state on new line so Enter + subsequent typing group as one undo.
        undoManager.continueAfterStructuralChange(state.focusedLineIndex)
    }

    private fun applyContent(line: LineState, newContent: String, contentCursor: Int) {
        val converted = convertSourcePrefix(newContent, line.prefix)
        if (converted != null) {
            // updateFull avoids double-counting the prefix: convertSourcePrefix returns
            // text starting with a display prefix, which updateContent would treat as
            // additional content on top of the existing prefix.
            val newText = line.prefix + converted.text
            val newCursor = line.prefix.length + converted.cursor
            line.updateFull(newText, newCursor)
        } else {
            line.updateContent(newContent, contentCursor)
        }
    }

    // Without this, a save fired before Enter (e.g. Activity ON_STOP from rotation) would
    // carry a bare null id — the upstream-bug shape the recovery path warns about. Line 0
    // is excluded: its id is enforced from parentNoteId in toNoteLines, and an empty list
    // there means "fresh root doc to be allocated" for brand-new notes.
    private fun stampNoteIdSentinelIfNeeded(lineIndex: Int, line: LineState) {
        if (lineIndex > 0 && line.noteIds.isEmpty() && line.content.isNotEmpty()) {
            line.noteIds = listOf(NoteIdSentinel.new(NoteIdSentinel.Origin.TYPED))
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
     * The system reports that [lineIndex] just gained focus. Update our
     * model (focused line + undo boundary) but do NOT re-grab focus: the
     * line already has it. The previous version called
     * [EditorState.requestFocusUpdate] here, which bumps focusVersion,
     * which fires the focus-grabbing LaunchedEffect, which calls
     * requestFocus(), which fires this callback again — a 100Hz IME-show
     * pump that drove the activity to ON_STOP. Use [focusLine] (below)
     * when *intentionally* moving focus to a different line.
     */
    fun markLineFocused(lineIndex: Int) {
        if (lineIndex !in state.lines.indices) return
        if (lineIndex != state.focusedLineIndex) {
            undoManager.commitPendingUndoState(state)
            state.focusedLineIndex = lineIndex
            undoManager.beginEditingLine(state, lineIndex)
        }
    }

    /**
     * Programmatically move focus to [lineIndex]. Bumps focusVersion so
     * the editor's focus LaunchedEffect picks up the change and calls
     * requestFocus() on the line's FocusRequester.
     */
    fun focusLine(lineIndex: Int) {
        if (lineIndex !in state.lines.indices) return
        markLineFocused(lineIndex)
        state.requestFocusUpdate()
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
     * Replace the current selection with [text] as a single undoable
     * operation. Used by the IME's commitText path when a selection is
     * active (e.g. GBoard's suggestion-tap fires
     * setSelection(typoStart, typoEnd) → commitText(suggestion)). The
     * user perceives the swap as one action, so undo must restore the
     * pre-replacement state in one step.
     */
    fun replaceSelectionWithUndo(text: String) {
        if (!state.hasSelection) return
        undoManager.captureStateBeforeChange(state)
        state.replaceSelectionInternal(text)
        undoManager.beginEditingLine(state, state.focusedLineIndex)
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

    /**
     * Resolve a line's stable identity to its current lineIndex.
     *
     * Used by IME state ([LineImeState]) which binds to a stable id —
     * not a lineIndex — because structural mutations (split / merge /
     * reorder / paste) shift indices around. The id is the line's
     * [LineState.tempId] (stable for the lifetime of the LineState
     * instance). Returns null when the line has been removed; callers
     * should treat this as a no-op rather than an error.
     */
    fun indexOf(lineId: String): Int? {
        val idx = state.lines.indexOfFirst { it.tempId == lineId }
        return if (idx >= 0) idx else null
    }

    // =========================================================================
    // Operation-based mutation API (Stage 3)
    //
    // Earlier IME wiring sent the controller a full new content string per
    // keystroke; the controller had to *infer* the user's intent from a diff
    // (e.g., "this string contains a \n, must be a split"). That inference
    // was the sentinel-storm bug surface — a stale buffer reused a `\n` the
    // controller had already consumed.
    //
    // The new API takes operations directly. Intent is explicit:
    //   - replaceRange — replace a range within a content string. If the
    //     replacement contains \n, split across the lines.
    //   - deleteAroundCursor — delete N chars before / M chars after the
    //     cursor on a line; may merge with neighbors when crossing edges.
    //   - setCursorByLineId / setComposingRange — cursor / IME composition
    //     on a stable line identity.
    //
    // All ops take a lineId (the line's tempId). They no-op when the line
    // is gone (the LineImeState may outlive its line through a frame; this
    // keeps the API safe to call without external guarding). Cursor return
    // values use [CursorPos] — lineId + offset — so the caller can react
    // to splits that move the cursor onto a freshly-allocated line.
    // =========================================================================

    /**
     * Cursor position pinned to a stable line identity. Returned by ops
     * that may move the cursor onto a different line (e.g., a split or a
     * cross-boundary delete).
     */
    data class CursorPos(val lineId: String, val contentOffset: Int)

    /**
     * Replace [range] within the content (post-prefix) of the line
     * identified by [lineId] with [text].
     *
     * If [text] contains `\n`s, the line is split — each `\n` produces a
     * new line. The cursor lands at the end of the inserted text, which
     * is on the post-final-newline line when newlines were inserted.
     * Returns [CursorPos] for the cursor's home; null if [lineId] is gone.
     */
    fun replaceRange(lineId: String, range: IntRange, text: String): CursorPos? {
        val lineIndex = indexOf(lineId) ?: return null
        val line = state.lines.getOrNull(lineIndex) ?: return null
        val content = line.content
        val start = range.first.coerceIn(0, content.length)
        val end = (range.last + 1).coerceIn(start, content.length)
        if (!text.contains('\n')) {
            val newContent = content.substring(0, start) + text + content.substring(end)
            val cursorOffsetInNewContent = start + text.length
            updateLineContent(lineIndex, newContent, cursorOffsetInNewContent)
            return CursorPos(lineId, cursorOffsetInNewContent)
        }
        // Multi-line replacement: split the line at [start..end], then
        // insert each `\n`-separated chunk as its own line, with the
        // first chunk merged onto the original line's prefix portion
        // and the last chunk prepended to the original line's suffix.
        val replacementLines = text.split('\n')
        val prefixHalf = content.substring(0, start)
        val suffixHalf = content.substring(end)
        // First newline split: prefixHalf + first chunk on the original
        // line, last chunk + suffixHalf on the new line. Subsequent
        // newlines fold into the (now-focused) new line iteratively.
        val firstChunkContent = prefixHalf + replacementLines.first()
        val tailContent = replacementLines.drop(1).joinToString("\n") + suffixHalf
        updateLineContent(lineIndex, firstChunkContent + "\n" + tailContent, firstChunkContent.length + 1)
        // After the first split, the focused line holds tailContent.
        // If tailContent still contains `\n`, recurse on the focused line
        // until no newlines remain.
        var currentIdx = state.focusedLineIndex
        while (true) {
            val l = state.lines.getOrNull(currentIdx) ?: break
            if (!l.content.contains('\n')) break
            val nl = l.content.indexOf('\n')
            updateLineContent(currentIdx, l.content, nl + 1)
            currentIdx = state.focusedLineIndex
        }
        val focusedLine = state.lines.getOrNull(state.focusedLineIndex) ?: return null
        return CursorPos(
            lineId = focusedLine.tempId,
            contentOffset = focusedLine.contentCursorPosition,
        )
    }

    /**
     * Delete [before] characters left of the cursor and [after]
     * characters right of the cursor on the line identified by [lineId].
     * Crossing a line boundary triggers a merge with the previous /
     * next line, in which case the cursor lands on the surviving line.
     * Returns [CursorPos] for the cursor's new home; null if [lineId]
     * is gone.
     */
    fun deleteAroundCursor(lineId: String, before: Int, after: Int): CursorPos? {
        val lineIndex = indexOf(lineId) ?: return null
        val line = state.lines.getOrNull(lineIndex) ?: return null
        val cursor = line.contentCursorPosition

        if (before > 0 && cursor == 0) {
            // Cross-boundary backspace: merge with previous line.
            deleteBackward(lineIndex)
            val newIdx = state.focusedLineIndex
            val newLine = state.lines.getOrNull(newIdx) ?: return null
            return CursorPos(newLine.tempId, newLine.contentCursorPosition)
        }
        if (after > 0 && cursor >= line.content.length) {
            deleteForward(lineIndex)
            val newIdx = state.focusedLineIndex
            val newLine = state.lines.getOrNull(newIdx) ?: return null
            return CursorPos(newLine.tempId, newLine.contentCursorPosition)
        }

        val deleteStart = (cursor - before).coerceAtLeast(0)
        val deleteEnd = (cursor + after).coerceAtMost(line.content.length)
        if (deleteStart >= deleteEnd) {
            return CursorPos(lineId, cursor)
        }
        val newContent = line.content.substring(0, deleteStart) +
            line.content.substring(deleteEnd)
        updateLineContent(lineIndex, newContent, deleteStart)
        return CursorPos(lineId, deleteStart)
    }

    /**
     * Set the cursor on the line identified by [lineId] to
     * [contentOffset] (offset within the line's content, post-prefix).
     * No-op if the line is gone.
     */
    fun setCursorByLineId(lineId: String, contentOffset: Int): CursorPos? {
        val lineIndex = indexOf(lineId) ?: return null
        setContentCursor(lineIndex, contentOffset)
        return CursorPos(lineId, contentOffset)
    }

    /**
     * Set or clear the IME composing range on the line identified by
     * [lineId]. The range is in content (post-prefix) offsets. Pass
     * null to clear. No-op on a missing line.
     *
     * Composing state is metadata for the IME's underlining; it has no
     * effect on the line's text. The range is preserved across edits
     * to the line as long as it stays in bounds (callers are
     * responsible for clearing when the composition is invalidated by
     * structural changes).
     */
    fun setComposingRange(lineId: String, range: IntRange?) {
        val lineIndex = indexOf(lineId) ?: return
        val line = state.lines.getOrNull(lineIndex) ?: return
        line.composingRange = range
    }

    /** Read the current composing range on a line, or null if none. */
    fun getComposingRange(lineId: String): IntRange? {
        val lineIndex = indexOf(lineId) ?: return null
        return state.lines.getOrNull(lineIndex)?.composingRange
    }

    /**
     * After a save, replace sentinel-or-empty noteIds on the editor's
     * lines with the freshly-allocated real Firestore doc ids returned
     * by the save (`saveNoteWithChildren`'s `createdIds`, keyed by line
     * index). Real ids are never overwritten.
     *
     * Without this, lines stay stuck on the prior sentinel and every
     * subsequent save allocates *another* fresh doc, orphaning the
     * previous one.
     */
    fun applyNewlyAssignedNoteIds(newIds: Map<Int, String>) {
        for ((index, newId) in newIds) {
            val line = state.lines.getOrNull(index) ?: continue
            val head = line.noteIds.firstOrNull()
            if (head == null || NoteIdSentinel.isSentinel(head)) {
                line.noteIds = listOf(newId)
            }
        }
    }
}

/**
 * Remember an EditorController for the given EditorState.
 */
@Composable
fun rememberEditorController(state: EditorState): EditorController {
    return remember(state) { EditorController(state) }
}
