package org.alkaline.taskbrain.ui.currentnote.rendering

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import org.alkaline.taskbrain.ui.currentnote.EditorId
import org.alkaline.taskbrain.ui.currentnote.InlineEditState
import org.alkaline.taskbrain.ui.currentnote.LocalInlineEditState
import org.alkaline.taskbrain.ui.currentnote.LocalSelectionCoordinator
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
import org.alkaline.taskbrain.ui.currentnote.selection.EditorSelectionLayer
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
import org.alkaline.taskbrain.ui.currentnote.selection.SelectionBounds
import org.alkaline.taskbrain.ui.currentnote.selection.calculateHandlePosition
import org.alkaline.taskbrain.ui.currentnote.selection.pickDragAnchor
import org.alkaline.taskbrain.ui.currentnote.selection.SelectionHandles
import org.alkaline.taskbrain.ui.currentnote.selection.createMenuActions
import org.alkaline.taskbrain.ui.currentnote.EditorConfig
import org.alkaline.taskbrain.ui.currentnote.EditorController
import org.alkaline.taskbrain.ui.currentnote.EditorState
import org.alkaline.taskbrain.ui.currentnote.gestures.editorPointerInput
import org.alkaline.taskbrain.ui.currentnote.gestures.LineLayoutInfo
import org.alkaline.taskbrain.ui.currentnote.LineState
import org.alkaline.taskbrain.ui.currentnote.rememberEditorState
import org.alkaline.taskbrain.dsl.directives.DirectiveFinder
import org.alkaline.taskbrain.dsl.directives.DirectiveResult
import org.alkaline.taskbrain.dsl.directives.DirectiveSegment
import org.alkaline.taskbrain.dsl.ui.DirectiveEditRow
import org.alkaline.taskbrain.ui.currentnote.util.CompletedLineUtils
import org.alkaline.taskbrain.ui.currentnote.util.SymbolOverlay

// =============================================================================
// Handle Position Calculation
// =============================================================================

/**
 * Calculates and remembers selection handle positions.
 */
@Composable
internal fun rememberHandlePositions(
    state: EditorState,
    lineLayouts: List<LineLayoutInfo>,
    gutterOffsetPx: Float,
    directiveResults: Map<String, DirectiveResult>
): Pair<HandlePosition?, HandlePosition?> {
    fun handlePosition(isStartHandle: Boolean): HandlePosition? {
        if (!state.hasSelection) return null
        val anchor = pickDragAnchor(state.selection, isStartHandle)
        return calculateHandlePosition(
            anchor.anchorOffset, state, lineLayouts,
            forEndHandle = anchor.forEndHandle,
            directiveResults = directiveResults,
        )?.let { pos ->
            pos.copy(offset = Offset(pos.offset.x + gutterOffsetPx, pos.offset.y))
        }
    }

    val startHandlePosition by remember(state.selection, lineLayouts, gutterOffsetPx, directiveResults) {
        derivedStateOf { handlePosition(isStartHandle = true) }
    }
    val endHandlePosition by remember(state.selection, lineLayouts, gutterOffsetPx, directiveResults) {
        derivedStateOf { handlePosition(isStartHandle = false) }
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
    directiveCallbacks: DirectiveCallbacks = DirectiveCallbacks(),
    buttonCallbacks: ButtonCallbacks = ButtonCallbacks(),
    showCompleted: Boolean = true,
    onSymbolTap: ((lineIndex: Int, charOffsetInLine: Int) -> Unit)? = null,
    symbolOverlaysProvider: ((lineIndex: Int) -> List<SymbolOverlay>)? = null,
    modifier: Modifier = Modifier
) {
    // NOTE: We intentionally do NOT sync `text` → `state` here via updateFromText.
    //
    // The earlier code had a `remember(text) { if (state.text != text) state.updateFromText(text) }`
    // trap. That trap fired on every recomposition where the parent's `text` prop differed
    // from `state.text`, calling the lossy `updateFromText`. When `state.lines` happened to be
    // empty or stale at that moment, every line was reconstructed with empty noteIds — the root
    // of the content-drop bug observed in production.
    //
    // The state-of-truth flows in one direction only: state → onTextChange → parent. Every place
    // that needs to mutate the editor from outside (loader, directive mutations, alarm redo, etc.)
    // calls `editorState.initFromNoteLines` or `editorState.updateFromText` explicitly with the
    // correct context. The Compose `text` parameter exists for the parent's bookkeeping, not as
    // a back-channel to mutate the editor.
    state.onTextChange = onTextChange

    // Use the provided EditorController - the SINGLE CHANNEL for all state modifications

    // Core state
    val lineCount = state.lines.size
    val focusRequesters = remember(lineCount) { List(lineCount) { FocusRequester() } }
    val lineLayouts = rememberLineLayouts(lineCount)

    // UI state
    val gutterSelectionState = rememberGutterSelectionState()

    // Platform state
    val viewConfiguration = LocalViewConfiguration.current
    val density = LocalDensity.current

    // Focus management — hidden lines have no composable, so their FocusRequesters
    // are unattached. Guard here to avoid crashing on requestFocus().
    LaunchedEffect(externalFocusRequester, state.focusedLineIndex, state.stateVersion) {
        if (state.stateVersion > 0 && state.focusedLineIndex in focusRequesters.indices) {
            val lineTexts = state.lines.map { it.text }
            val hidden = CompletedLineUtils.computeHiddenIndices(lineTexts, showCompleted)
            val effectiveHidden = CompletedLineUtils.computeEffectiveHidden(
                hidden, controller.recentlyCheckedIndices.toSet(), lineTexts
            )
            val targetLine = if (state.focusedLineIndex in effectiveHidden) {
                CompletedLineUtils.nearestVisibleLine(lineTexts, state.focusedLineIndex, effectiveHidden)
            } else {
                state.focusedLineIndex
            }
            if (targetLine in focusRequesters.indices && targetLine !in effectiveHidden) {
                focusRequesters[targetLine].requestFocus()
            }
        }
    }

    val gutterOffsetPx = if (showGutter) with(density) { EditorConfig.GutterWidth.toPx() } else 0f

    EditorSelectionLayer(
        state = state,
        controller = controller,
        lineLayouts = lineLayouts,
        gutterOffsetPx = gutterOffsetPx,
        directiveResults = directiveResults,
        modifier = modifier
    ) { selectionConfig ->
        EditorRow(
            state = state,
            controller = controller,
            textStyle = textStyle,
            focusRequesters = focusRequesters,
            lineLayouts = lineLayouts,
            viewConfiguration = viewConfiguration,
            scrollState = scrollState,
            showGutter = showGutter,
            showCompleted = showCompleted,
            gutterSelectionState = gutterSelectionState,
            contextMenuState = selectionConfig.contextMenuState,
            onEditorFocusChanged = onEditorFocusChanged,
            onSelectionCompleted = selectionConfig.onSelectionCompleted,
            directiveResults = directiveResults,
            directiveCallbacks = directiveCallbacks,
            buttonCallbacks = buttonCallbacks,
            onSymbolTap = onSymbolTap,
            symbolOverlaysProvider = symbolOverlaysProvider
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
    showCompleted: Boolean,
    gutterSelectionState: GutterSelectionState,
    contextMenuState: ContextMenuState,
    onEditorFocusChanged: ((Boolean) -> Unit)?,
    onSelectionCompleted: () -> Unit,
    directiveResults: Map<String, DirectiveResult>,
    directiveCallbacks: DirectiveCallbacks,
    buttonCallbacks: ButtonCallbacks,
    onSymbolTap: ((lineIndex: Int, charOffsetInLine: Int) -> Unit)? = null,
    symbolOverlaysProvider: ((lineIndex: Int) -> List<SymbolOverlay>)? = null
) {
    // Track measured heights of directive edit rows (keyed by directive position key)
    val directiveEditHeights = remember { mutableStateMapOf<String, Int>() }

    // Compute hidden/effective-hidden once and pass to both gutter and content
    val lineTexts = state.lines.map { it.text }
    val recentlyCheckedSnapshot = remember(controller.recentlyCheckedIndices.toSet()) {
        controller.recentlyCheckedIndices.toSet()
    }
    val hiddenIndices = remember(lineTexts, showCompleted) {
        CompletedLineUtils.computeHiddenIndices(lineTexts, showCompleted)
    }
    val effectiveHidden = remember(hiddenIndices, recentlyCheckedSnapshot) {
        CompletedLineUtils.computeEffectiveHidden(hiddenIndices, recentlyCheckedSnapshot, lineTexts)
    }

    // Find lines with view directives — parent gutter renders per-view-line boxes instead
    val inlineEditState = LocalInlineEditState.current
    val gutterCoordinator = LocalSelectionCoordinator.current
    val inlineEditLineIndices = remember(directiveResults, lineTexts) {
        state.lines.indices.filter { lineIdx ->
            findViewNoteIdForLine(lineIdx, state, directiveResults) != null
        }.toSet()
    }

    Row(modifier = Modifier.fillMaxWidth()) {
        if (showGutter) {
            LineGutter(
                lineLayouts = lineLayouts,
                state = state,
                hiddenIndices = effectiveHidden,
                directiveResults = directiveResults,
                directiveEditHeights = directiveEditHeights,
                inlineEditLineIndices = inlineEditLineIndices,
                onLineSelected = { lineIndex, yPosition ->
                    val coordinator = gutterCoordinator
                    if (lineIndex in inlineEditLineIndices) {
                        val res = resolveViewLineAtY(lineIndex, yPosition, lineLayouts, inlineEditState, directiveResults, state)
                        if (res != null) {
                            coordinator?.activate(EditorId.View(res.noteId))
                            res.sessionGutter.selectLine(res.viewLineIndex, res.sessionState)
                        }
                    } else {
                        coordinator?.activate(EditorId.Parent)
                        if (lineIndex in effectiveHidden) {
                            selectHiddenBlock(lineIndex, effectiveHidden, gutterSelectionState, state)
                        } else {
                            gutterSelectionState.selectLine(lineIndex, state)
                        }
                    }
                    onSelectionCompleted()
                },
                onLineDragStart = { lineIndex, yPosition ->
                    val coordinator = gutterCoordinator
                    if (lineIndex in inlineEditLineIndices) {
                        val res = resolveViewLineAtY(lineIndex, yPosition, lineLayouts, inlineEditState, directiveResults, state)
                        if (res != null) {
                            coordinator?.activate(EditorId.View(res.noteId))
                            res.sessionGutter.startDrag(res.viewLineIndex, res.sessionState)
                        }
                    } else {
                        coordinator?.activate(EditorId.Parent)
                        if (lineIndex in effectiveHidden) {
                            selectHiddenBlock(lineIndex, effectiveHidden, gutterSelectionState, state)
                        } else {
                            gutterSelectionState.startDrag(lineIndex, state)
                        }
                    }
                },
                onLineDragUpdate = { lineIndex, yPosition ->
                    if (lineIndex in inlineEditLineIndices) {
                        val res = resolveViewLineAtY(lineIndex, yPosition, lineLayouts, inlineEditState, directiveResults, state)
                        if (res != null) {
                            res.sessionGutter.extendSelectionToLine(res.viewLineIndex, res.sessionState)
                        }
                    } else if (lineIndex in effectiveHidden) {
                        val blockEnd = findHiddenBlockEnd(lineIndex, effectiveHidden, state.lines.size)
                        gutterSelectionState.extendSelectionToLine(blockEnd, state)
                    } else {
                        gutterSelectionState.extendSelectionToLine(lineIndex, state)
                    }
                },
                onLineDragEnd = {
                    gutterSelectionState.endDrag()
                    inlineEditState?.viewGutterStates?.values?.forEach { it.endDrag() }
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
            lineTexts = lineTexts,
            hiddenIndices = hiddenIndices,
            effectiveHidden = effectiveHidden,
            onCursorPositioned = controller::setCursorFromGlobalOffset,
            onTapOnSelection = contextMenuState::handleTapOnSelection,
            onSelectionCompleted = { onSelectionCompleted() },
            onEditorFocusChanged = onEditorFocusChanged,
            directiveResults = directiveResults,
            directiveCallbacks = directiveCallbacks,
            buttonCallbacks = buttonCallbacks,
            inlineEditLineIndices = inlineEditLineIndices,
            onDirectiveEditHeightMeasured = { key, height -> directiveEditHeights[key] = height },
            onSymbolTap = onSymbolTap,
            symbolOverlaysProvider = symbolOverlaysProvider,
            modifier = Modifier.weight(1f)
        )
    }
}

// =============================================================================
// Hidden Block Selection Helpers
// =============================================================================

/**
 * Finds the end of a contiguous hidden block starting at (or containing) lineIndex.
 */
private fun findHiddenBlockEnd(lineIndex: Int, hiddenIndices: Set<Int>, lineCount: Int): Int {
    var end = lineIndex
    while (end + 1 < lineCount && (end + 1) in hiddenIndices) {
        end++
    }
    return end
}

/**
 * Selects an entire hidden block via gutter gesture.
 * Sets the drag anchor to the block start so dragging extends correctly,
 * and moves focus to the nearest visible line to avoid crashing on
 * unattached FocusRequesters.
 */
private fun selectHiddenBlock(
    lineIndex: Int,
    hiddenIndices: Set<Int>,
    gutterSelectionState: GutterSelectionState,
    state: EditorState
) {
    // Find full contiguous hidden block
    var blockStart = lineIndex
    while (blockStart - 1 >= 0 && (blockStart - 1) in hiddenIndices) {
        blockStart--
    }
    val blockEnd = findHiddenBlockEnd(lineIndex, hiddenIndices, state.lines.size)

    // Select the entire block
    val selStart = state.getLineStartOffset(blockStart)
    var selEnd = state.getLineStartOffset(blockEnd) + state.lines[blockEnd].text.length
    if (blockEnd < state.lines.lastIndex) {
        selEnd = state.getLineStartOffset(blockEnd + 1)
    }
    if (selEnd > selStart) {
        state.setSelection(selStart, selEnd)
    }
    // Set drag anchor so extending works correctly
    gutterSelectionState.dragStartLine = blockStart

    // Move focus to nearest visible line to avoid crash on unattached FocusRequester
    val lineTexts = state.lines.map { it.text }
    val visibleFocus = CompletedLineUtils.nearestVisibleLine(lineTexts, blockStart, hiddenIndices)
    state.focusedLineIndex = visibleFocus
}

// =============================================================================
// Selection Overlay
// =============================================================================

@Composable
internal fun SelectionOverlay(
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
    } else if (state.hasSelection) {
        // Fallback for selections on hidden lines: find nearest line with a valid layout
        computeFallbackSelectionBounds(state, lineLayouts)
    } else null

    // Route context menu actions to the active editor via SelectionCoordinator
    val coordinatorForOverlay = LocalSelectionCoordinator.current
    val menuState = coordinatorForOverlay?.activeState ?: state
    val menuController = coordinatorForOverlay?.activeController ?: controller

    SelectionContextMenu(
        expanded = contextMenuState.isVisible,
        onDismissRequest = contextMenuState::dismiss,
        menuOffset = contextMenuState.position,
        actions = createMenuActions(
            state = menuState,
            controller = menuController,
            clipboardManager = clipboardManager,
            onDismiss = { contextMenuState.isVisible = false }
        ),
        selectionBounds = selectionBounds
    )
}

/**
 * Computes fallback SelectionBounds when handle positions are null
 * (e.g. selection is entirely on hidden lines). Uses the nearest line
 * with a valid layout as the anchor position.
 */
private fun computeFallbackSelectionBounds(
    state: EditorState,
    lineLayouts: List<LineLayoutInfo>
): SelectionBounds? {
    val (selStartLine, _) = state.getLineAndLocalOffset(state.selection.min)
    // Search outward from selection start for a line with valid layout
    val nearestLayout = lineLayouts.filter { it.textLayoutResult != null }
        .minByOrNull { kotlin.math.abs(it.lineIndex - selStartLine) }
        ?: return null
    val y = nearestLayout.yOffset
    val h = nearestLayout.height
    return SelectionBounds(
        startOffset = Offset(0f, y),
        endOffset = Offset(0f, y),
        startLineHeight = h,
        endLineHeight = h
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
    lineTexts: List<String>,
    hiddenIndices: Set<Int>,
    effectiveHidden: Set<Int>,
    onCursorPositioned: (Int) -> Unit,
    onTapOnSelection: (Offset) -> Unit,
    onSelectionCompleted: () -> Unit,
    onEditorFocusChanged: ((Boolean) -> Unit)?,
    directiveResults: Map<String, DirectiveResult>,
    directiveCallbacks: DirectiveCallbacks,
    buttonCallbacks: ButtonCallbacks,
    onDirectiveEditHeightMeasured: ((directiveKey: String, heightPx: Int) -> Unit)?,
    inlineEditLineIndices: Set<Int> = emptySet(),
    onSymbolTap: ((lineIndex: Int, charOffsetInLine: Int) -> Unit)? = null,
    symbolOverlaysProvider: ((lineIndex: Int) -> List<SymbolOverlay>)? = null,
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
                density = LocalDensity.current.density,
                scrollState = scrollState,
                directiveResults = directiveResults,
                inlineEditLineIndices = inlineEditLineIndices,
                onCursorPositioned = onCursorPositioned,
                onTapOnSelection = onTapOnSelection,
                onSelectionCompleted = { _ -> onSelectionCompleted() }
            )
    ) {
        // Observe stateVersion to trigger recomposition when state changes
        @Suppress("UNUSED_VARIABLE")
        val stateTrigger = state.stateVersion

        val displayItems = remember(lineTexts, effectiveHidden) {
            CompletedLineUtils.computeDisplayItemsFromHidden(lineTexts, effectiveHidden)
        }

        // Clear stale layout data for hidden lines so gesture hit-testing can't match them.
        // Without this, hidden lines retain yOffset/height from when they were last rendered,
        // causing taps to map to hidden line indices instead of visible ones.
        remember(effectiveHidden) {
            for (idx in effectiveHidden) {
                if (idx < lineLayouts.size) {
                    lineLayouts[idx] = LineLayoutInfo(idx, 0f, 0f, null)
                }
            }
            effectiveHidden
        }
        val recentlyCheckedSnapshot = remember(controller.recentlyCheckedIndices.toSet()) {
            controller.recentlyCheckedIndices.toSet()
        }
        val fadedIndices = remember(hiddenIndices, recentlyCheckedSnapshot) {
            CompletedLineUtils.computeFadedIndices(hiddenIndices, recentlyCheckedSnapshot, lineTexts)
        }

        // Snap focus/selection when lines become newly hidden (e.g. toggling showCompleted).
        // Track previous hidden set to only react to actual changes, not initial composition.
        var prevEffectiveHidden by remember { mutableStateOf<Set<Int>>(emptySet()) }
        LaunchedEffect(effectiveHidden) {
            val newlyHidden = effectiveHidden - prevEffectiveHidden
            prevEffectiveHidden = effectiveHidden
            if (newlyHidden.isEmpty()) return@LaunchedEffect

            if (state.focusedLineIndex in newlyHidden) {
                val newFocus = CompletedLineUtils.nearestVisibleLine(lineTexts, state.focusedLineIndex, effectiveHidden)
                controller.setCursor(newFocus, state.lines.getOrNull(newFocus)?.cursorPosition ?: 0)
            }
            if (state.hasSelection) {
                state.clearSelection()
            }
        }

        for (item in displayItems) {
            when (item) {
                is CompletedLineUtils.DisplayItem.CompletedPlaceholder -> {
                    CompletedPlaceholderRow(
                        count = item.count,
                        indentLevel = item.indentLevel,
                        textStyle = textStyle
                    )
                }
                is CompletedLineUtils.DisplayItem.VisibleLine -> {
                    val index = item.realIndex
                    val lineState = state.lines.getOrNull(index) ?: return@Column
                    val lineAlpha = if (index in fadedIndices) 0.4f else 1f
                    if (index < focusRequesters.size) {
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
                            directiveCallbacks = directiveCallbacks,
                            buttonCallbacks = buttonCallbacks,
                            onSymbolTap = onSymbolTap,
                            symbolOverlays = symbolOverlaysProvider?.invoke(index) ?: emptyList(),
                            alpha = lineAlpha
                        )

                        // Render edit rows for expanded directives on this line
                        val lineContent = lineState.content
                        val lineDirectives = DirectiveFinder.findDirectives(lineContent)
                        for (found in lineDirectives) {
                            val hashKey = DirectiveResult.hashDirective(found.sourceText)
                            val result = directiveResults[hashKey]
                            val isViewDirective = result?.toValue() is org.alkaline.taskbrain.dsl.runtime.values.ViewVal
                                || DirectiveSegment.Directive.isViewDirective(found.sourceText)
                            if (result != null && !result.collapsed && !isViewDirective) {
                                key(hashKey) {
                                    DirectiveEditRow(
                                        initialText = found.sourceText,
                                        textStyle = textStyle,
                                        errorMessage = result.error,
                                        warningMessage = result.warning?.displayMessage,
                                        onRefresh = { newText ->
                                            directiveCallbacks.onViewDirectiveRefresh?.invoke(index, hashKey, found.sourceText, newText)
                                        },
                                        onConfirm = { newText ->
                                            directiveCallbacks.onViewDirectiveConfirm?.invoke(index, hashKey, found.sourceText, newText)
                                        },
                                        onCancel = {
                                            directiveCallbacks.onViewDirectiveCancel?.invoke(index, hashKey, found.sourceText)
                                        },
                                        onHeightMeasured = { height ->
                                            onDirectiveEditHeightMeasured?.invoke(hashKey, height)
                                        }
                                    )
                                }
                            }
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
    directiveCallbacks: DirectiveCallbacks = DirectiveCallbacks(),
    buttonCallbacks: ButtonCallbacks = ButtonCallbacks(),
    onSymbolTap: ((lineIndex: Int, charOffsetInLine: Int) -> Unit)? = null,
    symbolOverlays: List<SymbolOverlay> = emptyList(),
    alpha: Float = 1f
) {
    val wrapperCoordinator = LocalSelectionCoordinator.current
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
                wrapperCoordinator?.activate(EditorId.Parent)
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
        directiveCallbacks = directiveCallbacks,
        buttonCallbacks = buttonCallbacks,
        onSymbolTap = onSymbolTap,
        symbolOverlays = symbolOverlays,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(alpha)
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

/** Resolve Y position within a view-directive parent line to a view-line index. */
private data class ViewLineResolution(
    val viewLineIndex: Int,
    val sessionState: EditorState,
    val sessionGutter: org.alkaline.taskbrain.ui.currentnote.selection.GutterSelectionState,
    val noteId: String
)

/** Resolve Y position within a view-directive parent line to a view-line index.
 *  Iterates all notes in the view to find which note the Y position falls in. */
private fun resolveViewLineAtY(
    parentLineIndex: Int,
    yPosition: Float,
    parentLineLayouts: List<LineLayoutInfo>,
    inlineEditState: InlineEditState?,
    directiveResults: Map<String, DirectiveResult>,
    parentState: EditorState,
    defaultLineHeight: Float = 57f
): ViewLineResolution? {
    if (inlineEditState == null) return null
    val viewNotes = findViewNotesForLine(parentLineIndex, parentState, directiveResults)
    if (viewNotes.isNullOrEmpty()) return null

    val parentLayout = parentLineLayouts.getOrNull(parentLineIndex) ?: return null
    val relativeY = yPosition - parentLayout.yOffset
    // Approximate dp→px using the ratio between measured and nominal default line height (24dp)
    val separatorHeightPx = EditorConfig.NoteSeparatorHeight.value * (defaultLineHeight / 24f)
    val metrics = computeViewLayoutMetrics(viewNotes, inlineEditState, parentLayout.height, separatorHeightPx, defaultLineHeight)
    var adjustedY = relativeY - metrics.topGap

    for ((noteIdx, note) in viewNotes.withIndex()) {
        if (noteIdx > 0) {
            if (adjustedY < separatorHeightPx) {
                val prevNote = viewNotes[noteIdx - 1]
                return resolveToLastLine(prevNote, inlineEditState)
            }
            adjustedY -= separatorHeightPx
        }

        val layouts = inlineEditState.viewLineLayouts[note.id]
        val session = inlineEditState.viewSessions[note.id] ?: continue
        val gutter = inlineEditState.viewGutterStates[note.id] ?: continue
        val noteState = session.editorState

        if (adjustedY < metrics.noteHeights[noteIdx]) {
            var viewLineIndex = 0
            var accumulatedY = 0f
            for (i in noteState.lines.indices) {
                val h = layouts?.getOrNull(i)?.height?.takeIf { it > 0f } ?: defaultLineHeight
                if (adjustedY < accumulatedY + h) { viewLineIndex = i; break }
                accumulatedY += h
                viewLineIndex = i
            }
            return ViewLineResolution(viewLineIndex, noteState, gutter, note.id)
        }
        adjustedY -= metrics.noteHeights[noteIdx]
    }

    return resolveToLastLine(viewNotes.last(), inlineEditState)
}

private fun resolveToLastLine(
    note: org.alkaline.taskbrain.data.Note,
    inlineEditState: InlineEditState
): ViewLineResolution? {
    val session = inlineEditState.viewSessions[note.id] ?: return null
    val gutter = inlineEditState.viewGutterStates[note.id] ?: return null
    return ViewLineResolution(
        session.editorState.lines.lastIndex.coerceAtLeast(0),
        session.editorState,
        gutter,
        note.id
    )
}


