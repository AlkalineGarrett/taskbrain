package org.alkaline.taskbrain.ui.currentnote.selection

import org.alkaline.taskbrain.ui.currentnote.EditorState

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Manages state for gutter-based line selection.
 *
 * Tracks the starting line of a gutter drag operation to support
 * extending selection across multiple lines.
 */
class GutterSelectionState {
    /** The line where a gutter drag started, or -1 if no drag in progress */
    var dragStartLine by mutableIntStateOf(-1)
        internal set

    /**
     * Selects an entire line (including the trailing newline).
     *
     * @param lineIndex The index of the line to select
     * @param state The editor state to update
     */
    fun selectLine(lineIndex: Int, state: EditorState) {
        state.selectLineRange(lineIndex..lineIndex)
    }

    /**
     * Extends selection from the drag start line to the specified line.
     * Used during gutter drag to select multiple lines.
     *
     * @param lineIndex The current line during drag
     * @param state The editor state to update
     */
    fun extendSelectionToLine(lineIndex: Int, state: EditorState) {
        if (dragStartLine < 0) return
        state.selectLineRange(minOf(dragStartLine, lineIndex)..maxOf(dragStartLine, lineIndex))
    }

    /**
     * Starts a gutter drag at the specified line.
     */
    fun startDrag(lineIndex: Int, state: EditorState) {
        dragStartLine = lineIndex
        selectLine(lineIndex, state)
    }

    /**
     * Ends the gutter drag.
     */
    fun endDrag() {
        dragStartLine = -1
    }
}

/**
 * Creates and remembers a GutterSelectionState instance.
 */
@Composable
fun rememberGutterSelectionState(): GutterSelectionState {
    return remember { GutterSelectionState() }
}
