package org.alkaline.taskbrain.ui.currentnote.selection

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import org.alkaline.taskbrain.R
import org.alkaline.taskbrain.ui.currentnote.EditorController
import org.alkaline.taskbrain.ui.currentnote.EditorState

/**
 * Context menu actions that can be performed on selected text.
 */
data class SelectionMenuActions(
    val onCopy: () -> Unit,
    val onCut: () -> Unit,
    val onSelectAll: () -> Unit,
    val onUnselect: () -> Unit,
    val onDelete: () -> Unit
)

/**
 * Selection bounds for positioning the context menu.
 */
data class SelectionBounds(
    val startOffset: Offset,
    val endOffset: Offset,
    val startLineHeight: Float,
    val endLineHeight: Float
) {
    /** True if selection spans multiple lines */
    val isMultiline: Boolean get() = startOffset.y != endOffset.y

    /** Top Y coordinate of the selection */
    val topY: Float get() = minOf(startOffset.y, endOffset.y)

    /** Bottom Y coordinate of the selection (including line height) */
    val bottomY: Float get() = maxOf(startOffset.y + startLineHeight, endOffset.y + endLineHeight)

    /** Leftmost X coordinate of the selection */
    val leftX: Float get() = minOf(startOffset.x, endOffset.x)

    /** Rightmost X coordinate of the selection */
    val rightX: Float get() = maxOf(startOffset.x, endOffset.x)
}

// Approximate menu dimensions for positioning calculations
private val MENU_WIDTH = 150.dp
private val MENU_MARGIN = 8.dp

/**
 * Context menu displayed when text is selected.
 * Provides copy, cut, select all, unselect, and delete actions.
 *
 * Positioning strategy:
 * - Position to the left or right of the selection (whichever has more space)
 * - Multi-line selection: prefer right edge of screen (less text there typically)
 * - Vertically centered on the selection
 */
@Composable
fun SelectionContextMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    menuOffset: Offset,
    actions: SelectionMenuActions,
    selectionBounds: SelectionBounds? = null
) {
    val density = LocalDensity.current

    BoxWithConstraints {
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val menuWidthPx = with(density) { MENU_WIDTH.toPx() }
        val marginPx = with(density) { MENU_MARGIN.toPx() }

        val offset = with(density) {
            if (selectionBounds != null) {
                // Calculate vertical position: centered on selection
                val centerY = (selectionBounds.topY + selectionBounds.bottomY) / 2
                val y = (centerY - 100f).coerceAtLeast(marginPx)  // Offset up a bit since menu expands down

                // Calculate horizontal position: left or right of selection
                val spaceOnRight = screenWidthPx - selectionBounds.rightX
                val spaceOnLeft = selectionBounds.leftX

                val x = if (selectionBounds.isMultiline) {
                    // Multi-line: prefer right edge (text typically doesn't extend there)
                    screenWidthPx - menuWidthPx - marginPx
                } else if (spaceOnRight >= menuWidthPx + marginPx * 2) {
                    // Enough space on right - position to the right of selection
                    selectionBounds.rightX + marginPx
                } else if (spaceOnLeft >= menuWidthPx + marginPx * 2) {
                    // Enough space on left - position to the left of selection
                    selectionBounds.leftX - menuWidthPx - marginPx
                } else {
                    // Not enough space on either side - position at right edge
                    screenWidthPx - menuWidthPx - marginPx
                }

                // Clamp: never position left of screen center
                val minX = screenWidthPx / 2f
                DpOffset(x.coerceAtLeast(minX).toDp(), y.toDp())
            } else {
                // Fallback: use provided offset, clamped to right half
                val minX = screenWidthPx / 2f
                DpOffset(
                    menuOffset.x.coerceAtLeast(minX).toDp(),
                    menuOffset.y.toDp() - 48.dp
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            offset = offset,
            // Don't take focus so keyboard stays open
            properties = PopupProperties(focusable = false)
        ) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_copy)) },
            leadingIcon = {
                Icon(
                    painter = painterResource(id = R.drawable.ic_copy),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            onClick = actions.onCopy
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_cut)) },
            leadingIcon = {
                Icon(
                    painter = painterResource(id = R.drawable.ic_cut),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            onClick = actions.onCut
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_select_all)) },
            leadingIcon = {
                Icon(
                    painter = painterResource(id = R.drawable.ic_select_all),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            onClick = actions.onSelectAll
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_unselect)) },
            leadingIcon = {
                Icon(
                    painter = painterResource(id = R.drawable.ic_deselect),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            onClick = actions.onUnselect
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_delete)) },
            leadingIcon = {
                Icon(
                    painter = painterResource(id = R.drawable.ic_delete),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            onClick = actions.onDelete
        )
        }
    }
}

/**
 * Creates SelectionMenuActions for the selection context menu.
 *
 * @param state The editor state
 * @param controller The editor controller for handling operations
 * @param clipboardManager The clipboard manager for copy/cut operations
 * @param onDismiss Callback to dismiss the menu after an action
 */
fun createMenuActions(
    state: EditorState,
    controller: EditorController,
    clipboardManager: ClipboardManager,
    onDismiss: () -> Unit
): SelectionMenuActions = SelectionMenuActions(
    onCopy = {
        controller.copySelection(clipboardManager)
        onDismiss()
    },
    onCut = {
        controller.cutSelection(clipboardManager)
        onDismiss()
    },
    onSelectAll = {
        state.selectAll()
        onDismiss()
    },
    onUnselect = {
        controller.clearSelection()
        onDismiss()
    },
    onDelete = {
        controller.deleteSelectionWithUndo()
        onDismiss()
    }
)
