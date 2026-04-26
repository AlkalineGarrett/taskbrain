package org.alkaline.taskbrain.ui.currentnote.ime

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.TextStyle
import org.alkaline.taskbrain.dsl.ui.DirectiveEditRow
import org.alkaline.taskbrain.ui.currentnote.EditorId
import org.alkaline.taskbrain.ui.currentnote.InlineEditSession
import org.alkaline.taskbrain.ui.currentnote.LocalInlineEditState
import org.alkaline.taskbrain.ui.currentnote.LocalParentShowCompleted
import org.alkaline.taskbrain.ui.currentnote.LocalSelectionCoordinator
import org.alkaline.taskbrain.ui.currentnote.gestures.LineLayoutInfo
import org.alkaline.taskbrain.ui.currentnote.gestures.editorPointerInput
import org.alkaline.taskbrain.ui.currentnote.rendering.CompletedPlaceholderRow
import org.alkaline.taskbrain.ui.currentnote.rendering.ControlledLineView
import org.alkaline.taskbrain.ui.currentnote.rendering.DirectiveCallbacks
import org.alkaline.taskbrain.ui.currentnote.selection.EditorSelectionLayer
import org.alkaline.taskbrain.ui.currentnote.selection.rememberGutterSelectionState
import org.alkaline.taskbrain.ui.currentnote.util.CompletedLineUtils
import org.alkaline.taskbrain.ui.currentnote.util.LocalSymbolOverlaysProvider

/**
 * Inline editor for a note within a view directive.
 * Uses the InlineEditSession's EditorState and EditorController for full editing support.
 * Commands from CommandBar route to this controller when active.
 *
 * Uses focusGroup() to track focus at the editor level — focus loss is only reported
 * when focus leaves the entire editor, not when it moves between lines.
 *
 * Each line has its own FocusRequester and IME connection for proper line move support.
 *
 * Renders directives properly — collapsed by default, tappable to expand directive editor.
 * Shows DirectiveEditRow below lines with expanded directives.
 */
@Composable
internal fun InlineNoteEditor(
    session: InlineEditSession,
    textStyle: TextStyle,
    onFocusChanged: (Boolean) -> Unit,
    /** Whether to auto-focus on mount. False for display mode to prevent unwanted editing. */
    autoFocus: Boolean = true,
    modifier: Modifier = Modifier
) {
    val editorState = session.editorState
    val controller = session.controller
    val directiveResults = session.directiveResults
    val inlineEditState = LocalInlineEditState.current
    val symbolOverlaysLookup = LocalSymbolOverlaysProvider.current

    val expandedDirectiveKey = session.expandedDirectiveKey
    val expandedDirectiveSourceText = session.expandedDirectiveSourceText

    @Suppress("UNUSED_VARIABLE")
    val stateTrigger = editorState.stateVersion

    // Per-line focus requesters — incrementally grown/shrunk to avoid recreating all
    // requesters on line count change (which would detach focused elements and cause
    // transient focus loss that triggers unwanted save).
    val lineCount = editorState.lines.size
    val lineFocusRequesters = remember { mutableListOf<FocusRequester>() }
    while (lineFocusRequesters.size < lineCount) lineFocusRequesters.add(FocusRequester())
    while (lineFocusRequesters.size > lineCount) lineFocusRequesters.removeAt(lineFocusRequesters.lastIndex)

    var isEditorFocused by remember { mutableStateOf(false) }
    // Track line count at last focus gain — if it changed when focus is lost,
    // it's a structural edit (backspace/enter), not the user leaving the editor.
    var lineCountAtFocusGain by remember { mutableIntStateOf(lineCount) }
    // Track focus guard version at last focus gain — if it changed when focus is lost,
    // a guarded operation (e.g. line move) caused transient focus loss.
    val selectionCoordinator = LocalSelectionCoordinator.current
    var guardVersionAtFocusGain by remember { mutableIntStateOf(selectionCoordinator?.focusGuardVersion ?: 0) }

    // Track line layouts for gutter hit testing (must be observable state for LineGutter recomposition)
    val lineLayouts = remember { mutableStateListOf<LineLayoutInfo>() }
    if (lineLayouts.size != lineCount) {
        while (lineLayouts.size < lineCount) {
            lineLayouts.add(LineLayoutInfo(lineLayouts.size, 0f, 0f, null))
        }
        while (lineLayouts.size > lineCount) {
            lineLayouts.removeAt(lineLayouts.lastIndex)
        }
    }
    val gutterSelectionState = rememberGutterSelectionState()

    // Track which line is expecting focus from a tap (to prevent false focus loss)
    var pendingFocusLineIndex by remember { mutableIntStateOf(-1) }

    // Request focus when the user taps a line (stateVersion changes).
    // mountVersion lets us distinguish user taps (which increment stateVersion)
    // from the initial composition (where it's already > 0 from a previous interaction).
    val mountVersion = remember(session) { editorState.stateVersion }
    LaunchedEffect(editorState.focusedLineIndex, editorState.stateVersion) {
        val isUserTap = editorState.stateVersion > mountVersion
        val shouldFocus = isUserTap && editorState.focusedLineIndex in lineFocusRequesters.indices
        if (shouldFocus) {
            try {
                lineFocusRequesters[editorState.focusedLineIndex].requestFocus()
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(expandedDirectiveKey) {
        if (expandedDirectiveKey == null && session.isCollapsingDirective) {
            val focusedIndex = editorState.focusedLineIndex
            if (focusedIndex in lineFocusRequesters.indices) {
                try {
                    lineFocusRequesters[focusedIndex].requestFocus()
                } catch (_: Exception) {
                    session.clearCollapsingFlag()
                }
            }
        }
    }

    LaunchedEffect(autoFocus) {
        if (!autoFocus) return@LaunchedEffect
        try {
            if (editorState.focusedLineIndex in lineFocusRequesters.indices) {
                lineFocusRequesters[editorState.focusedLineIndex].requestFocus()
            }
        } catch (_: Exception) {}
    }

    // Layouts and gutter state are keyed by noteId so they survive session
    // transitions; remove on dispose to prevent leaks across long-lived inline
    // edit state when notes drop out of view.
    DisposableEffect(inlineEditState, session.noteId) {
        inlineEditState?.viewLineLayouts?.set(session.noteId, lineLayouts)
        inlineEditState?.viewGutterStates?.set(session.noteId, gutterSelectionState)
        onDispose {
            inlineEditState?.viewLineLayouts?.remove(session.noteId)
            inlineEditState?.viewGutterStates?.remove(session.noteId)
        }
    }

    // The parent editor's showCompleted overrides this embedded note's own setting.
    // When opened standalone (no parent context), parentShowCompleted is null and we
    // leave hidden indices empty.
    val parentShowCompleted = LocalParentShowCompleted.current
    val lineTexts = editorState.lines.map { it.text }
    val hiddenIndices = remember(lineTexts, parentShowCompleted) {
        if (parentShowCompleted == false) {
            CompletedLineUtils.computeHiddenIndices(lineTexts, false)
        } else {
            emptySet()
        }
    }
    val recentlyCheckedSnapshot = remember(controller.recentlyCheckedIndices.toSet()) {
        controller.recentlyCheckedIndices.toSet()
    }
    val effectiveHidden = remember(hiddenIndices, recentlyCheckedSnapshot, lineTexts) {
        CompletedLineUtils.computeEffectiveHidden(hiddenIndices, recentlyCheckedSnapshot, lineTexts)
    }
    val displayItems = remember(lineTexts, effectiveHidden) {
        CompletedLineUtils.computeDisplayItemsFromHidden(lineTexts, effectiveHidden)
    }
    controller.hiddenIndices = effectiveHidden
    // Clear stale layout data for hidden lines so gesture hit-testing can't match them.
    remember(effectiveHidden) {
        for (idx in effectiveHidden) {
            if (idx < lineLayouts.size) {
                lineLayouts[idx] = LineLayoutInfo(idx, 0f, 0f, null)
            }
        }
        effectiveHidden
    }

    val viewConfiguration = LocalViewConfiguration.current

    EditorSelectionLayer(
        state = editorState,
        controller = controller,
        lineLayouts = lineLayouts,
        directiveResults = directiveResults
    ) { selectionConfig ->

        Column(
            modifier = modifier
                .focusGroup()
                .editorPointerInput(
                    state = editorState,
                    lineLayouts = lineLayouts,
                    longPressTimeoutMillis = viewConfiguration.longPressTimeoutMillis,
                    touchSlop = viewConfiguration.touchSlop,
                    density = LocalDensity.current.density,
                    scrollState = null,
                    directiveResults = emptyMap(),
                    onCursorPositioned = controller::setCursorFromGlobalOffset,
                    onTapOnSelection = selectionConfig.contextMenuState::handleTapOnSelection,
                    onSelectionCompleted = { _ -> selectionConfig.onSelectionCompleted() }
                )
                .onFocusChanged { focusState ->
                    val wasFocused = isEditorFocused
                    isEditorFocused = focusState.hasFocus
                    if (focusState.hasFocus && !wasFocused) {
                        lineCountAtFocusGain = editorState.lines.size
                        guardVersionAtFocusGain = selectionCoordinator?.focusGuardVersion ?: 0
                        onFocusChanged(true)
                    } else if (!focusState.hasFocus && wasFocused) {
                        val lineCountChanged = editorState.lines.size != lineCountAtFocusGain
                        val guardVersionChanged = (selectionCoordinator?.focusGuardVersion ?: 0) != guardVersionAtFocusGain
                        if (pendingFocusLineIndex >= 0 || lineCountChanged || guardVersionChanged) {
                            // Suppress transient focus loss from line moves or guarded operations
                        } else {
                            onFocusChanged(false)
                        }
                    }
                }
        ) {
            // Render lines using DisplayItem so completed subtrees collapse into a
            // "(N completed)" placeholder when the parent's showCompleted is false —
            // mirroring HangingIndentEditor's main-editor behavior.
            for (item in displayItems) {
                when (item) {
                    is CompletedLineUtils.DisplayItem.CompletedPlaceholder -> {
                        val blockStart = item.blockStartIndex
                        CompletedPlaceholderRow(
                            count = item.count,
                            indentLevel = item.indentLevel,
                            textStyle = textStyle,
                            onHeightMeasured = { height ->
                                if (blockStart in lineLayouts.indices &&
                                    lineLayouts[blockStart].height != height
                                ) {
                                    lineLayouts[blockStart] = lineLayouts[blockStart].copy(height = height)
                                }
                            }
                        )
                    }
                    is CompletedLineUtils.DisplayItem.VisibleLine -> {
                        val index = item.realIndex
                        val lineState = editorState.lines.getOrNull(index) ?: continue
                        if (index >= lineFocusRequesters.size) continue
                        val lineSelection = editorState.getLineSelection(index)
                        val lineEndOffset = editorState.getLineStartOffset(index) + lineState.text.length
                        val selectionIncludesNewline = editorState.hasSelection &&
                            editorState.selection.min <= lineEndOffset &&
                            editorState.selection.max > lineEndOffset &&
                            index < editorState.lines.lastIndex

                        ControlledLineView(
                            lineIndex = index,
                            lineState = lineState,
                            controller = controller,
                            textStyle = textStyle,
                            focusRequester = lineFocusRequesters[index],
                            selectionRange = lineSelection,
                            selectionIncludesNewline = selectionIncludesNewline,
                            onFocusChanged = { isFocused ->
                                if (isFocused) {
                                    pendingFocusLineIndex = -1
                                    controller.focusLine(index)
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
                            symbolOverlays = symbolOverlaysLookup(lineState.content),
                            directiveResults = directiveResults,
                            directiveCallbacks = DirectiveCallbacks(
                                onDirectiveTap = { directiveKey, sourceText ->
                                    inlineEditState?.toggleDirectiveExpanded(directiveKey, sourceText)
                                }
                            ),
                            onTapStarting = {
                                pendingFocusLineIndex = index
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned { coordinates ->
                                    if (index < lineLayouts.size) {
                                        val pos = coordinates.positionInParent()
                                        val h = coordinates.size.height.toFloat()
                                        lineLayouts[index] = lineLayouts[index].copy(
                                            lineIndex = index,
                                            yOffset = pos.y,
                                            height = h
                                        )
                                    }
                                }
                        )

                        // Expanded directive editor below this line
                        val expandedOnThisLine = expandedDirectiveKey?.startsWith("$index:") == true
                        if (expandedOnThisLine && expandedDirectiveSourceText != null) {
                            val result = directiveResults[expandedDirectiveKey]
                            DirectiveEditRow(
                                initialText = expandedDirectiveSourceText,
                                textStyle = textStyle,
                                errorMessage = result?.error,
                                warningMessage = result?.warning?.displayMessage,
                                onRefresh = { currentText ->
                                    inlineEditState?.refreshDirective(expandedDirectiveKey, currentText)
                                },
                                onConfirm = { newText ->
                                    inlineEditState?.confirmDirective(
                                        index, expandedDirectiveKey,
                                        expandedDirectiveSourceText, newText
                                    )
                                },
                                onCancel = { inlineEditState?.collapseDirective() }
                            )
                        }
                    }
                }
            }
        }
    }
}
