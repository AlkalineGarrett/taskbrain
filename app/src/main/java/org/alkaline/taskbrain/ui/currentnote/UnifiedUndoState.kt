package org.alkaline.taskbrain.ui.currentnote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import org.alkaline.taskbrain.data.Note
import org.alkaline.taskbrain.ui.currentnote.undo.UnifiedUndoManager

/**
 * Result of [rememberUnifiedUndoState].
 *
 * @property manager The unified undo/redo manager — pass through to UI handlers
 *   that need to call undo()/redo().
 * @property canUndo Snapshot of the manager's undo availability. Reading subscribes
 *   to recomposition via [UnifiedUndoManager.stateVersion].
 * @property canRedo Snapshot of the manager's redo availability.
 */
class UnifiedUndoState(
    val manager: UnifiedUndoManager,
    val canUndo: Boolean,
    val canRedo: Boolean,
)

const val MAIN_CONTEXT_ID = "main"

private const val INLINE_CONTEXT_PREFIX = "inline:"

fun inlineContextId(noteId: String) = "$INLINE_CONTEXT_PREFIX$noteId"

fun noteIdFromInlineContextId(contextId: String): String = contextId.removePrefix(INLINE_CONTEXT_PREFIX)

/**
 * Builds a [UnifiedUndoManager] that tracks undo/redo across the main editor and
 * every inline edit session for the current view notes. Registers/unregisters
 * editors via DisposableEffects keyed on the controller and on the view-note list,
 * so adding or removing a view note rebinds its inline session automatically.
 *
 * Reads `stateVersion` from both [editorState] and the unified manager so that
 * any state mutation in either triggers recomposition of canUndo/canRedo.
 */
@Composable
fun rememberUnifiedUndoState(
    controller: EditorController,
    editorState: EditorState,
    viewNotes: List<Note>,
    inlineEditState: InlineEditState,
): UnifiedUndoState {
    val manager = remember { UnifiedUndoManager() }

    DisposableEffect(controller) {
        manager.registerEditor(MAIN_CONTEXT_ID, controller)
        onDispose { manager.unregisterEditor(MAIN_CONTEXT_ID) }
    }

    DisposableEffect(viewNotes) {
        for (note in viewNotes) {
            val session = inlineEditState.viewSessions[note.id]
            if (session != null) {
                manager.registerEditor(inlineContextId(note.id), session.controller)
            }
        }
        onDispose {
            for (note in viewNotes) {
                manager.unregisterEditor(inlineContextId(note.id))
            }
        }
    }

    // Subscribe to state changes so canUndo/canRedo recompose. Reading these
    // mutableIntState-backed properties registers the call site as a reader.
    @Suppress("UNUSED_VARIABLE") val editorStateVersion = editorState.stateVersion
    @Suppress("UNUSED_VARIABLE") val undoStateVersion = manager.stateVersion

    return UnifiedUndoState(
        manager = manager,
        canUndo = manager.canUndo,
        canRedo = manager.canRedo,
    )
}
