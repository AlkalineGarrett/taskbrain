package org.alkaline.taskbrain.ui.currentnote.undo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.alkaline.taskbrain.ui.currentnote.EditorController

/**
 * Coordinates undo/redo across multiple editors into a single chronological timeline.
 *
 * Each editor keeps its own [UndoManager] for internal mechanics (pending state,
 * command grouping, baseline). This manager tracks the ORDER of undo entries
 * across editors via callbacks, enabling unified undo/redo that feels like
 * editing a single document.
 */
class UnifiedUndoManager {
    private val editors = mutableMapOf<String, EditorController>()
    private val unifiedUndoStack = mutableListOf<String>() // context IDs
    private val unifiedRedoStack = mutableListOf<String>() // context IDs

    /** Reactive version counter for Compose recomposition. */
    var stateVersion by mutableIntStateOf(0)
        private set

    val canUndo: Boolean
        get() = unifiedUndoStack.isNotEmpty() ||
                editors.values.any { it.undoManager.currentHasUncommittedChanges }

    val canRedo: Boolean
        get() = unifiedRedoStack.isNotEmpty()

    fun registerEditor(contextId: String, controller: EditorController) {
        editors[contextId] = controller
        controller.undoManager.onEntryPushed = {
            unifiedUndoStack.add(contextId)
            stateVersion++
        }
        controller.undoManager.onRedoCleared = {
            unifiedRedoStack.clear()
            stateVersion++
        }
    }

    fun unregisterEditor(contextId: String) {
        editors[contextId]?.undoManager?.apply {
            onEntryPushed = null
            onRedoCleared = null
        }
        editors.remove(contextId)
    }

    /**
     * Perform unified undo.
     * @param activeContextId which editor currently has focus
     * @param activateEditor callback to switch focus to a different editor
     * @return the target context ID and snapshot, or null if nothing to undo
     */
    fun undo(
        activeContextId: String,
        activateEditor: (String) -> Unit
    ): UndoResult? {
        // Commit pending on the active editor first — may add to unified stack
        editors[activeContextId]?.commitUndoState()

        if (unifiedUndoStack.isEmpty()) return null

        val targetContextId = unifiedUndoStack.removeAt(unifiedUndoStack.lastIndex)
        val targetCtrl = editors[targetContextId] ?: return null

        // Switch editor if needed
        if (targetContextId != activeContextId) {
            activateEditor(targetContextId)
        }

        // Perform undo on the target editor (callbacks suppressed internally)
        val snapshot = targetCtrl.undo()
        if (snapshot != null) {
            unifiedRedoStack.add(targetContextId)
        }
        stateVersion++
        return snapshot?.let { UndoResult(targetContextId, it) }
    }

    /**
     * Perform unified redo.
     * @param activeContextId which editor currently has focus
     * @param activateEditor callback to switch focus to a different editor
     * @return the target context ID and snapshot, or null if nothing to redo
     */
    fun redo(
        activeContextId: String,
        activateEditor: (String) -> Unit
    ): UndoResult? {
        if (unifiedRedoStack.isEmpty()) return null

        val targetContextId = unifiedRedoStack.removeAt(unifiedRedoStack.lastIndex)
        val targetCtrl = editors[targetContextId] ?: return null

        // Switch editor if needed
        if (targetContextId != activeContextId) {
            activateEditor(targetContextId)
        }

        // Perform redo on the target editor (callbacks suppressed internally)
        val snapshot = targetCtrl.redo()
        if (snapshot != null) {
            unifiedUndoStack.add(targetContextId)
        }
        stateVersion++
        return snapshot?.let { UndoResult(targetContextId, it) }
    }

    /** Clear all unified history. Called when navigating to a direct view of an embedded note. */
    fun abandonHistory() {
        unifiedUndoStack.clear()
        unifiedRedoStack.clear()
        stateVersion++
    }

    /** Reset all state. Called when switching notes. */
    fun reset() {
        unifiedUndoStack.clear()
        unifiedRedoStack.clear()
        for (controller in editors.values) {
            controller.undoManager.onEntryPushed = null
            controller.undoManager.onRedoCleared = null
        }
        editors.clear()
        stateVersion++
    }
}

/** Result of a unified undo/redo operation. */
data class UndoResult(
    val contextId: String,
    val snapshot: UndoSnapshot
)
