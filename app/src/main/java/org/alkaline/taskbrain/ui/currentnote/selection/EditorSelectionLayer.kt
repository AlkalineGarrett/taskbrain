package org.alkaline.taskbrain.ui.currentnote.selection

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalClipboardManager
import org.alkaline.taskbrain.dsl.directives.DirectiveResult
import org.alkaline.taskbrain.ui.currentnote.EditorController
import org.alkaline.taskbrain.ui.currentnote.EditorState
import org.alkaline.taskbrain.ui.currentnote.LocalSelectionCoordinator
import org.alkaline.taskbrain.ui.currentnote.gestures.LineLayoutInfo
import org.alkaline.taskbrain.ui.currentnote.rendering.SelectionOverlay
import org.alkaline.taskbrain.ui.currentnote.rendering.rememberHandlePositions

/**
 * Selection infrastructure shared by both the main editor and inline view editors.
 * Wraps [content] and [SelectionOverlay] in a single Box so the overlay shares the
 * content's coordinate origin — handle offsets are computed in the editor column's
 * space and must render there. [modifier] is applied to the wrapping Box so any
 * padding reaches both children and the in-line selection highlight stays aligned
 * with the handles.
 */
@Composable
internal fun EditorSelectionLayer(
    state: EditorState,
    controller: EditorController,
    lineLayouts: List<LineLayoutInfo>,
    modifier: Modifier = Modifier,
    gutterOffsetPx: Float = 0f,
    directiveResults: Map<String, DirectiveResult> = emptyMap(),
    content: @Composable (SelectionConfig) -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val contextMenuState = rememberContextMenuState()
    val handleDragState = rememberHandleDragState()

    val selectionCoordinator = LocalSelectionCoordinator.current
    val onSelectionCompleted: () -> Unit = {
        if (selectionCoordinator?.hasAnySelection() == true || state.hasSelection) {
            contextMenuState.show(Offset.Zero)
        }
    }

    val (startHandlePosition, endHandlePosition) = rememberHandlePositions(
        state, lineLayouts, gutterOffsetPx, directiveResults
    )

    val config = SelectionConfig(
        contextMenuState = contextMenuState,
        onSelectionCompleted = onSelectionCompleted,
    )

    Box(modifier = modifier) {
        content(config)

        SelectionOverlay(
            state = state,
            controller = controller,
            lineLayouts = lineLayouts,
            handleDragState = handleDragState,
            startHandlePosition = startHandlePosition,
            endHandlePosition = endHandlePosition,
            contextMenuState = contextMenuState,
            clipboardManager = clipboardManager,
            directiveResults = directiveResults
        )
    }
}

/**
 * Selection configuration provided to editor content by [EditorSelectionLayer].
 */
data class SelectionConfig(
    val contextMenuState: ContextMenuState,
    val onSelectionCompleted: () -> Unit,
)
