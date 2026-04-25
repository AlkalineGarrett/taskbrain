package org.alkaline.taskbrain.ui.currentnote.selection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset

/**
 * Manages context menu visibility and position.
 * Handles the state machine for showing/dismissing the selection context menu.
 */
class ContextMenuState {
    var isVisible by mutableStateOf(false)
    var position by mutableStateOf(Offset.Zero)
    var justDismissed by mutableStateOf(false)

    /**
     * Shows the context menu at the specified position.
     */
    fun show(at: Offset) {
        position = at
        isVisible = true
    }

    /**
     * Dismisses the context menu and sets the justDismissed flag.
     */
    fun dismiss() {
        isVisible = false
        justDismissed = true
    }

    /**
     * Handles a tap on a selection.
     * - If we just dismissed the menu, clear the flag and do nothing
     * - If the menu is visible, dismiss it
     * - Otherwise, show the menu at the tap position
     */
    fun handleTapOnSelection(tapPosition: Offset) {
        when {
            justDismissed -> justDismissed = false
            isVisible -> isVisible = false
            else -> show(tapPosition)
        }
    }
}

/**
 * Creates and remembers a ContextMenuState.
 */
@Composable
fun rememberContextMenuState(): ContextMenuState {
    return remember { ContextMenuState() }
}

/**
 * Per-line tap handlers run before the editor-wide gesture handler can fire (their
 * [androidx.compose.foundation.gestures.detectTapGestures] consumes the touch). This local
 * exposes the active editor's "tap on selection" routine so those line handlers can
 * toggle the menu instead of clearing the selection. Provided by [EditorSelectionLayer];
 * null outside an editor.
 */
internal val LocalContextMenuTapHandler = compositionLocalOf<((Offset) -> Unit)?> { null }
