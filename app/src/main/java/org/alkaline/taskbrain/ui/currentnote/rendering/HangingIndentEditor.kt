package org.alkaline.taskbrain.ui.currentnote.rendering

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import org.alkaline.taskbrain.ui.currentnote.selection.ContextMenuState
import org.alkaline.taskbrain.ui.currentnote.selection.GutterSelectionState
import org.alkaline.taskbrain.ui.currentnote.selection.HandleDragState
import org.alkaline.taskbrain.ui.currentnote.selection.HandlePosition
import org.alkaline.taskbrain.ui.currentnote.selection.PrefixSelectionOverlay
import org.alkaline.taskbrain.ui.currentnote.selection.SelectionActions
import org.alkaline.taskbrain.ui.currentnote.selection.SelectionContextMenu
import org.alkaline.taskbrain.ui.currentnote.selection.SelectionMenuActions
import org.alkaline.taskbrain.ui.currentnote.selection.SelectionHandle
import org.alkaline.taskbrain.ui.currentnote.selection.rememberGutterSelectionState
import org.alkaline.taskbrain.ui.currentnote.selection.rememberHandleDragState
import org.alkaline.taskbrain.ui.currentnote.selection.SelectionBounds
import org.alkaline.taskbrain.ui.currentnote.selection.calculateHandlePosition
import org.alkaline.taskbrain.ui.currentnote.selection.SelectionHandles
import org.alkaline.taskbrain.ui.currentnote.selection.createMenuActions
import org.alkaline.taskbrain.ui.currentnote.selection.rememberContextMenuState
import org.alkaline.taskbrain.ui.currentnote.EditorConfig
import org.alkaline.taskbrain.ui.currentnote.EditorController
import org.alkaline.taskbrain.ui.currentnote.EditorState
import org.alkaline.taskbrain.ui.currentnote.HangingIndentEditorState
import org.alkaline.taskbrain.ui.currentnote.gestures.editorPointerInput
import org.alkaline.taskbrain.ui.currentnote.gestures.LineLayoutInfo
import org.alkaline.taskbrain.ui.currentnote.LineState
import org.alkaline.taskbrain.ui.currentnote.rememberEditorState
import org.alkaline.taskbrain.dsl.directives.DirectiveFinder
import org.alkaline.taskbrain.dsl.directives.DirectiveResult
import org.alkaline.taskbrain.dsl.ui.DirectiveEditRow

// =============================================================================
// Handle Position Calculation
// =============================================================================

/**
 * Calculates and remembers selection handle positions.
 */
@Composable
private fun rememberHandlePositions(
    state: EditorState,
    lineLayouts: List<LineLayoutInfo>,
    gutterOffsetPx: Float,
    directiveResults: Map<String, DirectiveResult>
): Pair<HandlePosition?, HandlePosition?> {
    val startHandlePosition by remember(state.selection, lineLayouts, gutterOffsetPx, directiveResults) {
        derivedStateOf {
            if (state.hasSelection) {
                calculateHandlePosition(state.selection.min, state, lineLayouts, directiveResults = directiveResults)?.let { pos ->
                    pos.copy(offset = Offset(pos.offset.x + gutterOffsetPx, pos.offset.y))
                }
            } else null
        }
    }

    val endHandlePosition by remember(state.selection, lineLayouts, gutterOffsetPx, directiveResults) {
        derivedStateOf {
            if (state.hasSelection) {
                calculateHandlePosition(state.selection.max, state, lineLayouts, forEndHandle = true, directiveResults = directiveResults)?.let { pos ->
                    pos.copy(offset = Offset(pos.offset.x + gutterOffsetPx, pos.offset.y))
                }
            } else null
        }
    }

    return startHandlePosition to endHandlePosition
}

// =============================================================================
// Main Editor Composable
// =============================================================================

/**
 * Multi-line text editor with hanging indent support.
 * Each line is rendered with its prefix (tabs + bullet/checkbox) at fixed width,
 * and content that wraps within the remaining space.
 *
 * Features:
 * - Hanging indent: wrapped lines start at the same position as the first line's content
 * - Cross-line text selection via long-press and drag
 * - Bullet and checkbox prefix support
 * - Tab indentation
 */
@Composable
fun HangingIndentEditor(
    text: String,
    onTextChange: (String) -> Unit,
    textStyle: TextStyle = TextStyle(fontSize = EditorConfig.FontSize, color = Color.Black),
    state: EditorState = rememberEditorState(),
    controller: EditorController,
    externalFocusRequester: FocusRequester? = null,
    onEditorFocusChanged: ((Boolean) -> Unit)? = null,
    scrollState: ScrollState? = null,
    showGutter: Boolean = false,
    directiveResults: Map<String, DirectiveResult> = emptyMap(),
    onDirectiveTap: ((directiveKey: String, sourceText: String) -> Unit)? = null,
    onDirectiveEditConfirm: ((lineIndex: Int, directiveKey: String, sourceText: String, newText: String) -> Unit)? = null,
    onDirectiveEditCancel: ((lineIndex: Int, directiveKey: String, sourceText: String) -> Unit)? = null,
    onDirectiveRefresh: ((lineIndex: Int, directiveKey: String, sourceText: String, newText: String) -> Unit)? = null,
    onViewNoteTap: ((directiveKey: String, noteId: String, noteContent: String) -> Unit)? = null,
    onViewEditDirective: ((directiveKey: String, sourceText: String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // Sync state with text prop
    remember(text) {
        if (state.text != text) state.updateFromText(text)
        text
    }
    state.onTextChange = onTextChange

    // Use the provided EditorController - the SINGLE CHANNEL for all state modifications

    // Core state
    val lineCount = state.lines.size
    val focusRequesters = remember(lineCount) { List(lineCount) { FocusRequester() } }
    val lineLayouts = rememberLineLayouts(lineCount)

    // UI state
    val contextMenuState = rememberContextMenuState()
    val gutterSelectionState = rememberGutterSelectionState()
    val handleDragState = rememberHandleDragState()

    // Platform state
    val viewConfiguration = LocalViewConfiguration.current
    val clipboardManager = LocalClipboardManager.current
    val density = LocalDensity.current

    // SINGLE callback for all selection completion events
    // This ensures consistent behavior regardless of how selection was made
    // (long-press drag, gutter tap, gutter drag, etc.)
    val onSelectionCompleted: () -> Unit = {
        if (state.hasSelection) {
            // Position doesn't matter - menu positioning uses selectionBounds
            contextMenuState.show(Offset.Zero)
        }
    }

    // Focus management - triggers when focusedLineIndex or stateVersion changes
    // Only request focus when stateVersion > 0 to avoid focusing before content loads.
    // stateVersion starts at 0 and is incremented by requestFocusUpdate() after content loads.
    LaunchedEffect(externalFocusRequester, state.focusedLineIndex, state.stateVersion) {
        if (state.stateVersion > 0 && state.focusedLineIndex in focusRequesters.indices) {
            focusRequesters[state.focusedLineIndex].requestFocus()
        }
    }

    // Handle positions
    val gutterOffsetPx = if (showGutter) with(density) { EditorConfig.GutterWidth.toPx() } else 0f
    val (startHandlePosition, endHandlePosition) = rememberHandlePositions(state, lineLayouts, gutterOffsetPx, directiveResults)

    // Render
    EditorLayout(
        state = state,
        controller = controller,
        textStyle = textStyle,
        focusRequesters = focusRequesters,
        lineLayouts = lineLayouts,
        viewConfiguration = viewConfiguration,
        scrollState = scrollState,
        showGutter = showGutter,
        gutterSelectionState = gutterSelectionState,
        handleDragState = handleDragState,
        startHandlePosition = startHandlePosition,
        endHandlePosition = endHandlePosition,
        contextMenuState = contextMenuState,
        clipboardManager = clipboardManager,
        onEditorFocusChanged = onEditorFocusChanged,
        onSelectionCompleted = onSelectionCompleted,
        directiveResults = directiveResults,
        onDirectiveTap = onDirectiveTap,
        onDirectiveEditConfirm = onDirectiveEditConfirm,
        onDirectiveEditCancel = onDirectiveEditCancel,
        onDirectiveRefresh = onDirectiveRefresh,
        onViewNoteTap = onViewNoteTap,
        onViewEditDirective = onViewEditDirective,
        modifier = modifier
    )
}

// =============================================================================
// Editor Layout
// =============================================================================

@Composable
private fun EditorLayout(
    state: EditorState,
    controller: EditorController,
    textStyle: TextStyle,
    focusRequesters: List<FocusRequester>,
    lineLayouts: MutableList<LineLayoutInfo>,
    viewConfiguration: ViewConfiguration,
    scrollState: ScrollState?,
    showGutter: Boolean,
    gutterSelectionState: GutterSelectionState,
    handleDragState: HandleDragState,
    startHandlePosition: HandlePosition?,
    endHandlePosition: HandlePosition?,
    contextMenuState: ContextMenuState,
    clipboardManager: ClipboardManager,
    onEditorFocusChanged: ((Boolean) -> Unit)?,
    onSelectionCompleted: () -> Unit,
    directiveResults: Map<String, DirectiveResult>,
    onDirectiveTap: ((directiveKey: String, sourceText: String) -> Unit)?,
    onDirectiveEditConfirm: ((lineIndex: Int, directiveKey: String, sourceText: String, newText: String) -> Unit)?,
    onDirectiveEditCancel: ((lineIndex: Int, directiveKey: String, sourceText: String) -> Unit)?,
    onDirectiveRefresh: ((lineIndex: Int, directiveKey: String, sourceText: String, newText: String) -> Unit)?,
    onViewNoteTap: ((directiveKey: String, noteId: String, noteContent: String) -> Unit)?,
    onViewEditDirective: ((directiveKey: String, sourceText: String) -> Unit)?,
    modifier: Modifier
) {
    Box(modifier = modifier) {
        // Main content row
        EditorRow(
            state = state,
            controller = controller,
            textStyle = textStyle,
            focusRequesters = focusRequesters,
            lineLayouts = lineLayouts,
            viewConfiguration = viewConfiguration,
            scrollState = scrollState,
            showGutter = showGutter,
            gutterSelectionState = gutterSelectionState,
            contextMenuState = contextMenuState,
            onEditorFocusChanged = onEditorFocusChanged,
            onSelectionCompleted = onSelectionCompleted,
            directiveResults = directiveResults,
            onDirectiveTap = onDirectiveTap,
            onDirectiveEditConfirm = onDirectiveEditConfirm,
            onDirectiveEditCancel = onDirectiveEditCancel,
            onDirectiveRefresh = onDirectiveRefresh,
            onViewNoteTap = onViewNoteTap,
            onViewEditDirective = onViewEditDirective
        )

        // Selection overlay (handles + context menu)
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

@Composable
private fun EditorRow(
    state: EditorState,
    controller: EditorController,
    textStyle: TextStyle,
    focusRequesters: List<FocusRequester>,
    lineLayouts: MutableList<LineLayoutInfo>,
    viewConfiguration: ViewConfiguration,
    scrollState: ScrollState?,
    showGutter: Boolean,
    gutterSelectionState: GutterSelectionState,
    contextMenuState: ContextMenuState,
    onEditorFocusChanged: ((Boolean) -> Unit)?,
    onSelectionCompleted: () -> Unit,
    directiveResults: Map<String, DirectiveResult>,
    onDirectiveTap: ((directiveKey: String, sourceText: String) -> Unit)?,
    onDirectiveEditConfirm: ((lineIndex: Int, directiveKey: String, sourceText: String, newText: String) -> Unit)?,
    onDirectiveEditCancel: ((lineIndex: Int, directiveKey: String, sourceText: String) -> Unit)?,
    onDirectiveRefresh: ((lineIndex: Int, directiveKey: String, sourceText: String, newText: String) -> Unit)?,
    onViewNoteTap: ((directiveKey: String, noteId: String, noteContent: String) -> Unit)?,
    onViewEditDirective: ((directiveKey: String, sourceText: String) -> Unit)?
) {
    // Track measured heights of directive edit rows (keyed by directive position key)
    val directiveEditHeights = remember { mutableStateMapOf<String, Int>() }

    Row(modifier = Modifier.fillMaxWidth()) {
        if (showGutter) {
            LineGutter(
                lineLayouts = lineLayouts,
                state = state,
                directiveResults = directiveResults,
                directiveEditHeights = directiveEditHeights,
                onLineSelected = { lineIndex ->
                    gutterSelectionState.selectLine(lineIndex, state)
                    onSelectionCompleted()
                },
                onLineDragStart = { gutterSelectionState.startDrag(it, state) },
                onLineDragUpdate = { gutterSelectionState.extendSelectionToLine(it, state) },
                onLineDragEnd = {
                    gutterSelectionState.endDrag()
                    onSelectionCompleted()
                }
            )
        }

        EditorContent(
            state = state,
            controller = controller,
            textStyle = textStyle,
            focusRequesters = focusRequesters,
            lineLayouts = lineLayouts,
            viewConfiguration = viewConfiguration,
            scrollState = scrollState,
            onCursorPositioned = controller::setCursorFromGlobalOffset,
            onTapOnSelection = contextMenuState::handleTapOnSelection,
            onSelectionCompleted = { onSelectionCompleted() },
            onEditorFocusChanged = onEditorFocusChanged,
            directiveResults = directiveResults,
            onDirectiveTap = onDirectiveTap,
            onDirectiveEditConfirm = onDirectiveEditConfirm,
            onDirectiveEditCancel = onDirectiveEditCancel,
            onDirectiveRefresh = onDirectiveRefresh,
            onDirectiveEditHeightMeasured = { key, height -> directiveEditHeights[key] = height },
            onViewNoteTap = onViewNoteTap,
            onViewEditDirective = onViewEditDirective,
            modifier = Modifier.weight(1f)
        )
    }
}

// =============================================================================
// Selection Overlay
// =============================================================================

@Composable
private fun SelectionOverlay(
    state: EditorState,
    controller: EditorController,
    lineLayouts: List<LineLayoutInfo>,
    handleDragState: HandleDragState,
    startHandlePosition: HandlePosition?,
    endHandlePosition: HandlePosition?,
    contextMenuState: ContextMenuState,
    clipboardManager: ClipboardManager,
    directiveResults: Map<String, DirectiveResult>
) {
    // Selection handles
    if (state.hasSelection) {
        SelectionHandles(
            startPosition = startHandlePosition,
            endPosition = endHandlePosition,
            onStartHandleDrag = { handleDragState.updateSelectionFromDrag(true, it, state, lineLayouts, directiveResults) },
            onEndHandleDrag = { handleDragState.updateSelectionFromDrag(false, it, state, lineLayouts, directiveResults) },
            onStartHandleDragEnd = { handleDragState.resetDragState(true) },
            onEndHandleDragEnd = { handleDragState.resetDragState(false) }
        )
    }

    // Context menu - compute selection bounds for optimal positioning
    val selectionBounds = if (state.hasSelection && startHandlePosition != null && endHandlePosition != null) {
        SelectionBounds(
            startOffset = startHandlePosition.offset,
            endOffset = endHandlePosition.offset,
            startLineHeight = startHandlePosition.lineHeight,
            endLineHeight = endHandlePosition.lineHeight
        )
    } else null

    SelectionContextMenu(
        expanded = contextMenuState.isVisible,
        onDismissRequest = contextMenuState::dismiss,
        menuOffset = contextMenuState.position,
        actions = createMenuActions(
            state = state,
            controller = controller,
            clipboardManager = clipboardManager,
            onDismiss = { contextMenuState.isVisible = false }
        ),
        selectionBounds = selectionBounds
    )
}

// =============================================================================
// Line Layouts
// =============================================================================

@Composable
private fun rememberLineLayouts(lineCount: Int): MutableList<LineLayoutInfo> {
    // Don't use lineCount as key - we want to preserve layout data across line count changes
    val lineLayouts = remember {
        mutableStateListOf<LineLayoutInfo>()
    }

    // Adjust size to match line count, preserving existing layout data
    if (lineLayouts.size != lineCount) {
        while (lineLayouts.size < lineCount) {
            lineLayouts.add(LineLayoutInfo(lineLayouts.size, 0f, 0f, null))
        }
        while (lineLayouts.size > lineCount) {
            lineLayouts.removeAt(lineLayouts.lastIndex)
        }
    }

    return lineLayouts
}

// =============================================================================
// Editor Content
// =============================================================================

@Composable
private fun EditorContent(
    state: EditorState,
    controller: EditorController,
    textStyle: TextStyle,
    focusRequesters: List<FocusRequester>,
    lineLayouts: MutableList<LineLayoutInfo>,
    viewConfiguration: ViewConfiguration,
    scrollState: ScrollState?,
    onCursorPositioned: (Int) -> Unit,
    onTapOnSelection: (Offset) -> Unit,
    onSelectionCompleted: () -> Unit,
    onEditorFocusChanged: ((Boolean) -> Unit)?,
    directiveResults: Map<String, DirectiveResult>,
    onDirectiveTap: ((directiveKey: String, sourceText: String) -> Unit)?,
    onDirectiveEditConfirm: ((lineIndex: Int, directiveKey: String, sourceText: String, newText: String) -> Unit)?,
    onDirectiveEditCancel: ((lineIndex: Int, directiveKey: String, sourceText: String) -> Unit)?,
    onDirectiveRefresh: ((lineIndex: Int, directiveKey: String, sourceText: String, newText: String) -> Unit)?,
    onDirectiveEditHeightMeasured: ((directiveKey: String, heightPx: Int) -> Unit)?,
    onViewNoteTap: ((directiveKey: String, noteId: String, noteContent: String) -> Unit)?,
    onViewEditDirective: ((directiveKey: String, sourceText: String) -> Unit)?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .focusGroup()
            .editorPointerInput(
                state = state,
                lineLayouts = lineLayouts,
                longPressTimeoutMillis = viewConfiguration.longPressTimeoutMillis,
                touchSlop = viewConfiguration.touchSlop,
                scrollState = scrollState,
                directiveResults = directiveResults,
                onCursorPositioned = onCursorPositioned,
                onTapOnSelection = onTapOnSelection,
                // Wrap the callback to ignore the Offset parameter - we use selectionBounds for positioning
                onSelectionCompleted = { _ -> onSelectionCompleted() }
            )
    ) {
        // Observe stateVersion to trigger recomposition when state changes
        @Suppress("UNUSED_VARIABLE")
        val stateTrigger = state.stateVersion

        state.lines.forEachIndexed { index, lineState ->
            if (index < focusRequesters.size) {
                // All lines use the same wrapper - directives are handled inline
                ControlledLineViewWrapper(
                    index = index,
                    lineState = lineState,
                    state = state,
                    controller = controller,
                    textStyle = textStyle,
                    focusRequester = focusRequesters[index],
                    lineLayouts = lineLayouts,
                    onEditorFocusChanged = onEditorFocusChanged,
                    directiveResults = directiveResults,
                    onDirectiveTap = { key, sourceText -> onDirectiveTap?.invoke(key, sourceText) },
                    onViewNoteTap = onViewNoteTap,
                    onViewEditDirective = onViewEditDirective,
                    onViewDirectiveRefresh = onDirectiveRefresh,
                    onViewDirectiveConfirm = onDirectiveEditConfirm,
                    onViewDirectiveCancel = onDirectiveEditCancel
                )

                // Render edit rows for expanded directives on this line
                // Skip view directives - they render DirectiveEditRow inside ViewDirectiveInlineContent
                val lineContent = lineState.content
                val lineDirectives = DirectiveFinder.findDirectives(lineContent)
                for (found in lineDirectives) {
                    val key = DirectiveFinder.directiveKey(index, found.startOffset)
                    val result = directiveResults[key]
                    // Skip view directives - they handle their own DirectiveEditRow at the top
                    val isViewDirective = result?.toValue() is org.alkaline.taskbrain.dsl.runtime.values.ViewVal
                    if (result != null && !result.collapsed && !isViewDirective) {
                        // Key on position-based key so component recreates when directive moves
                        key(key) {
                            DirectiveEditRow(
                                initialText = found.sourceText,
                                textStyle = textStyle,
                                errorMessage = result.error,
                                warningMessage = result.warning?.displayMessage,
                                onRefresh = { newText ->
                                    onDirectiveRefresh?.invoke(index, key, found.sourceText, newText)
                                },
                                onConfirm = { newText ->
                                    onDirectiveEditConfirm?.invoke(index, key, found.sourceText, newText)
                                },
                                onCancel = {
                                    onDirectiveEditCancel?.invoke(index, key, found.sourceText)
                                },
                                onHeightMeasured = { height ->
                                    onDirectiveEditHeightMeasured?.invoke(key, height)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Wrapper that provides ControlledLineView with necessary computed values.
 */
@Composable
private fun ControlledLineViewWrapper(
    index: Int,
    lineState: LineState,
    state: EditorState,
    controller: EditorController,
    textStyle: TextStyle,
    focusRequester: FocusRequester,
    lineLayouts: MutableList<LineLayoutInfo>,
    onEditorFocusChanged: ((Boolean) -> Unit)?,
    directiveResults: Map<String, DirectiveResult> = emptyMap(),
    onDirectiveTap: ((directiveKey: String, sourceText: String) -> Unit)? = null,
    onViewNoteTap: ((directiveKey: String, noteId: String, noteContent: String) -> Unit)? = null,
    onViewEditDirective: ((directiveKey: String, sourceText: String) -> Unit)? = null,
    onViewDirectiveRefresh: ((lineIndex: Int, directiveKey: String, sourceText: String, newText: String) -> Unit)? = null,
    onViewDirectiveConfirm: ((lineIndex: Int, directiveKey: String, sourceText: String, newText: String) -> Unit)? = null,
    onViewDirectiveCancel: ((lineIndex: Int, directiveKey: String, sourceText: String) -> Unit)? = null
) {
    val lineSelection = state.getLineSelection(index)
    val lineEndOffset = state.getLineStartOffset(index) + lineState.text.length
    val selectionIncludesNewline = state.hasSelection &&
        state.selection.min <= lineEndOffset &&
        state.selection.max > lineEndOffset &&
        index < state.lines.lastIndex

    ControlledLineView(
        lineIndex = index,
        lineState = lineState,
        controller = controller,
        textStyle = textStyle,
        focusRequester = focusRequester,
        selectionRange = lineSelection,
        selectionIncludesNewline = selectionIncludesNewline,
        onFocusChanged = { isFocused ->
            if (isFocused) {
                controller.focusLine(index)
                onEditorFocusChanged?.invoke(true)
            }
        },
        onTextLayoutResult = { layoutResult ->
            if (index < lineLayouts.size) {
                lineLayouts[index] = lineLayouts[index].copy(textLayoutResult = layoutResult)
            }
        },
        onPrefixWidthMeasured = { prefixWidthPx ->
            if (index < lineLayouts.size) {
                lineLayouts[index] = lineLayouts[index].copy(prefixWidthPx = prefixWidthPx)
            }
        },
        directiveResults = directiveResults,
        onDirectiveTap = onDirectiveTap,
        onViewNoteTap = onViewNoteTap,
        onViewEditDirective = onViewEditDirective,
        onViewDirectiveRefresh = onViewDirectiveRefresh,
        onViewDirectiveConfirm = onViewDirectiveConfirm,
        onViewDirectiveCancel = onViewDirectiveCancel,
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                if (index < lineLayouts.size) {
                    val pos = coordinates.positionInParent()
                    lineLayouts[index] = lineLayouts[index].copy(
                        lineIndex = index,
                        yOffset = pos.y,
                        height = coordinates.size.height.toFloat()
                    )
                }
            }
    )
}

