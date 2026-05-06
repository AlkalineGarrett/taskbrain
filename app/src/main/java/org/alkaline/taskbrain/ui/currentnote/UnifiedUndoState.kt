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

    // viewNotes is published before ensureSessionsForNotes finishes filling
    // inlineEditState.viewSessions, so keying on viewNotes alone runs this
    // effect when no sessions exist yet — leaving paste/undo on the embedded
    // editor disconnected from the unified stack. Reading viewSessions[id]
    // subscribes to that map entry, so the moment the suspending session
    // loader publishes a session the key list changes and we re-register.
    val inlineSessionIds = viewNotes.mapNotNull { note ->
        inlineEditState.viewSessions[note.id]?.let { note.id }
    }
    DisposableEffect(inlineSessionIds) {
        for (id in inlineSessionIds) {
            val session = inlineEditState.viewSessions[id] ?: continue
            manager.registerEditor(inlineContextId(id), session.controller)
        }
        onDispose {
            for (id in inlineSessionIds) {
                manager.unregisterEditor(inlineContextId(id))
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
