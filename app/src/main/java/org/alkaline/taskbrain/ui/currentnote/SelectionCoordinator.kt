package org.alkaline.taskbrain.ui.currentnote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Identifies which editor is active (has focus/selection rights).
 */
sealed class EditorId {
    data object Parent : EditorId()
    data class View(val noteId: String) : EditorId()
}

/**
 * Single source of truth for focus/selection mutual exclusivity between
 * the parent note editor and embedded view note editors.
 *
 * When one editor is activated, all others have their selections cleared.
 * Provides activeController/activeState for command routing.
 * Provides withFocusGuard to suppress focus-loss reactions during operations.
 */
@Stable
class SelectionCoordinator(
    private val parentState: EditorState,
    private val parentController: EditorController
) {
    /** Which editor is currently active (has focus/selection rights). */
    var activeEditorId: EditorId by mutableStateOf<EditorId>(EditorId.Parent)
        private set

    /** Focus guard depth — when > 0, focus loss should be ignored. */
    private var focusGuardDepth: Int = 0

    /**
     * Monotonically increasing counter, incremented each time [withFocusGuard] is called.
     * Inline editors record this value on focus gain and compare on focus loss —
     * if it changed, a guarded operation (e.g. line move) happened while focused,
     * so transient focus loss should be suppressed.
     */
    var focusGuardVersion: Int = 0
        private set

    // ===== Active editor properties =====

    val activeController: EditorController
        get() = when (val id = activeEditorId) {
            is EditorId.Parent -> parentController
            is EditorId.View -> inlineEditState?.viewSessions?.get(id.noteId)?.controller ?: parentController
        }

    val activeState: EditorState
        get() = when (val id = activeEditorId) {
            is EditorId.Parent -> parentState
            is EditorId.View -> inlineEditState?.viewSessions?.get(id.noteId)?.editorState ?: parentState
        }

    val activeSession: InlineEditSession?
        get() = when (val id = activeEditorId) {
            is EditorId.Parent -> null
            is EditorId.View -> inlineEditState?.viewSessions?.get(id.noteId)
        }

    /** Reference to InlineEditState for accessing view sessions. Set by CurrentNoteScreen. */
    var inlineEditState: InlineEditState? = null

    // ===== Activation / deactivation =====

    /** Activate an editor, clearing all other selections. */
    fun activate(editorId: EditorId) {
        if (editorId == activeEditorId) return
        clearAllSelections()
        activeEditorId = editorId
        // Also activate the InlineEditState session for directive execution etc.
        if (editorId is EditorId.View) {
            val session = inlineEditState?.viewSessions?.get(editorId.noteId)
            if (session != null) {
                inlineEditState?.activateExistingSession(session)
            }
        } else {
            inlineEditState?.endSession()
        }
    }

    /** Deactivate the current view editor, returning to parent. */
    fun deactivateToParent(): InlineEditSession? {
        val old = activeSession
        activeEditorId = EditorId.Parent
        return old
    }

    // ===== Mutual exclusivity =====

    private fun clearAllSelections() {
        parentState.clearSelection()
        inlineEditState?.viewSessions?.values?.forEach { it.editorState.clearSelection() }
    }

    // ===== Focus guard (replaces per-operation flags) =====

    val isFocusGuarded: Boolean get() = focusGuardDepth > 0

    /**
     * Run [action] with the focus guard active. The guard suppresses focus-loss
     * reactions (e.g. auto-save in inline editors) during the operation.
     *
     * The guard is decremented immediately when the action completes (in `finally`).
     * Surviving async focus callbacks that arrive after the guard is released is
     * handled by [focusGuardVersion]: inline editors record the version on focus
     * gain and compare on focus loss — if it changed, a guarded operation happened
     * while focused, so transient focus loss is suppressed.
     */
    fun <T> withFocusGuard(action: () -> T): T {
        focusGuardDepth++
        focusGuardVersion++
        try {
            return action()
        } finally {
            focusGuardDepth--
        }
    }

    // ===== Query methods =====

    /** True if any editor (parent or view) has a selection. */
    fun hasAnySelection(): Boolean {
        if (parentState.hasSelection) return true
        return inlineEditState?.viewSessions?.values?.any { it.editorState.hasSelection } == true
    }
}

val LocalSelectionCoordinator = compositionLocalOf<SelectionCoordinator?> { null }
