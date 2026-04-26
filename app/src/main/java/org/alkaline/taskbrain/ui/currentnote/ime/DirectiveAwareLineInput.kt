package org.alkaline.taskbrain.ui.currentnote.ime

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import org.alkaline.taskbrain.dsl.directives.DirectiveResult
import org.alkaline.taskbrain.dsl.directives.DirectiveSegmenter
import org.alkaline.taskbrain.dsl.directives.mapSourceToDisplayOffset
import org.alkaline.taskbrain.dsl.runtime.values.ButtonVal
import org.alkaline.taskbrain.dsl.runtime.values.ViewVal
import org.alkaline.taskbrain.dsl.ui.ButtonExecutionState
import org.alkaline.taskbrain.ui.currentnote.EditorController
import org.alkaline.taskbrain.ui.currentnote.rendering.ButtonCallbacks
import org.alkaline.taskbrain.ui.currentnote.rendering.DirectiveCallbacks
import org.alkaline.taskbrain.ui.currentnote.selection.LocalContextMenuTapHandler
import org.alkaline.taskbrain.ui.currentnote.util.SymbolOverlay
import org.alkaline.taskbrain.ui.currentnote.util.TappableSymbol
import org.alkaline.taskbrain.ui.currentnote.util.drawSymbolOverlays
import org.alkaline.taskbrain.ui.currentnote.util.hasVisibleBadges

/**
 * A directive-aware text input that allows editing around directives.
 *
 * Key features:
 * - Shows computed directive results inline (replacing source text visually)
 * - Directive results are displayed in dashed boxes and are tappable
 * - Non-directive text is fully editable
 * - Cursor position maps correctly between source and display
 * - View directives show note content with inline editing support
 *
 * Splits the rendering into three branches based on directive content:
 *  - No directives: a plain BasicText with cursor + symbol overlays.
 *  - View directive: [ViewDirectiveInlineContent] in [ViewDirectiveContent.kt].
 *  - Button directive: [ButtonDirectiveInlineContent] in [ButtonDirective.kt].
 *  - Otherwise (other directives): [DirectiveOverlayText] in [DirectiveOverlayText.kt],
 *    which handles the dashed boxes and invisible tap targets.
 */
@Composable
internal fun DirectiveAwareLineInput(
    lineIndex: Int,
    content: String,
    contentCursor: Int,
    controller: EditorController,
    isFocused: Boolean,
    hasExternalSelection: Boolean,
    textStyle: TextStyle,
    focusRequester: FocusRequester,
    directiveResults: Map<String, DirectiveResult>,
    onFocusChanged: (Boolean) -> Unit,
    onTextLayoutResult: (TextLayoutResult) -> Unit,
    directiveCallbacks: DirectiveCallbacks = DirectiveCallbacks(),
    buttonCallbacks: ButtonCallbacks = ButtonCallbacks(),
    onSymbolTap: ((lineIndex: Int, charOffsetInLine: Int) -> Unit)? = null,
    symbolOverlays: List<SymbolOverlay> = emptyList(),
    /** Called before a tap changes cursor/focus — used by inline editors to track pending focus. */
    onTapStarting: (() -> Unit)? = null,
    lineNoteId: String? = null,
    modifier: Modifier = Modifier
) {
    val onDirectiveTap = directiveCallbacks.onDirectiveTap
    val onViewNoteTap = directiveCallbacks.onViewNoteTap
    val onViewEditDirective = directiveCallbacks.onViewEditDirective
    val onViewDirectiveRefresh = directiveCallbacks.onViewDirectiveRefresh
    val onViewDirectiveConfirm = directiveCallbacks.onViewDirectiveConfirm
    val onViewDirectiveCancel = directiveCallbacks.onViewDirectiveCancel
    val onButtonClick = buttonCallbacks.onClick
    val buttonExecutionStates = buttonCallbacks.executionStates
    val buttonErrors = buttonCallbacks.errors

    val hostView = LocalView.current
    val imeState = remember(lineIndex, controller) {
        LineImeState(lineIndex, controller)
    }

    LaunchedEffect(content, contentCursor) {
        if (!imeState.isInBatchEdit) {
            imeState.syncFromController()
        }
    }

    val displayResult = remember(content, directiveResults, lineNoteId) {
        DirectiveSegmenter.buildDisplayText(content, directiveResults, lineNoteId)
    }

    val displayCursor = remember(contentCursor, displayResult) {
        mapSourceToDisplayOffset(contentCursor, displayResult)
    }

    val interactionSource = remember { MutableInteractionSource() }
    var textLayoutResultState: TextLayoutResult? by remember { mutableStateOf(null) }

    val infiniteTransition = rememberInfiniteTransition(label = "cursorBlink")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursorAlpha"
    )

    val cursorInDirective = remember(contentCursor, displayResult) {
        displayResult.directiveDisplayRanges.any { range ->
            contentCursor > range.sourceRange.first && contentCursor < range.sourceRange.last + 1
        }
    }

    val viewDirectiveRange = remember(displayResult, directiveResults) {
        displayResult.directiveDisplayRanges.find { range -> range.isView }
    }

    val viewVal = remember(viewDirectiveRange, directiveResults) {
        if (viewDirectiveRange != null) {
            val result = directiveResults[viewDirectiveRange.key]
            result?.toValue() as? ViewVal
        } else null
    }

    val buttonDirectiveRange = remember(displayResult, directiveResults) {
        displayResult.directiveDisplayRanges.find { range -> range.isButton }
    }

    val buttonVal = remember(buttonDirectiveRange, directiveResults) {
        if (buttonDirectiveRange != null) {
            val result = directiveResults[buttonDirectiveRange.key]
            result?.toValue() as? ButtonVal
        } else null
    }

    val onTapInsideSelection = LocalContextMenuTapHandler.current

    Box(
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged { focusState -> onFocusChanged(focusState.isFocused) }
            .lineImeConnection(imeState, contentCursor, hostView)
            .focusable(interactionSource = interactionSource)
    ) {
        if (displayResult.directiveDisplayRanges.isEmpty()) {
            val hasTappableSymbol = onSymbolTap != null && TappableSymbol.containsAny(content)
            val hasOverlays = symbolOverlays.hasVisibleBadges()
            val textMeasurer = if (hasOverlays) rememberTextMeasurer() else null
            BasicText(
                text = content,
                style = textStyle,
                onTextLayout = { layoutResult ->
                    textLayoutResultState = layoutResult
                    onTextLayoutResult(layoutResult)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(content, onSymbolTap, onTapStarting, controller, onTapInsideSelection) {
                        detectTapGestures { offset ->
                            val layout = textLayoutResultState ?: return@detectTapGestures
                            val position = layout.getOffsetForPosition(offset)
                            if (hasTappableSymbol) {
                                val symbolIndex = findTappedSymbol(
                                    content, position, layout::getBoundingBox, offset.x
                                )
                                if (symbolIndex != null) {
                                    onSymbolTap?.invoke(lineIndex, symbolIndex)
                                    return@detectTapGestures
                                }
                            }
                            if (onTapInsideSelection != null
                                && controller.isContentOffsetInSelection(lineIndex, position)) {
                                onTapInsideSelection(offset)
                                return@detectTapGestures
                            }
                            onTapStarting?.invoke()
                            controller.setContentCursor(lineIndex, position)
                        }
                    }
                    .drawCursor(
                        isFocused = isFocused,
                        hasExternalSelection = hasExternalSelection,
                        cursorPosition = contentCursor,
                        textLength = content.length,
                        textLayoutResultProvider = { textLayoutResultState },
                        cursorAlpha = cursorAlpha
                    )
                    .then(
                        if (hasOverlays && textMeasurer != null) {
                            Modifier.drawSymbolOverlays(
                                content = content,
                                textLayoutResult = textLayoutResultState,
                                overlays = symbolOverlays,
                                textMeasurer = textMeasurer
                            )
                        } else {
                            Modifier
                        }
                    )
            )
        } else if (viewDirectiveRange != null && viewVal != null && viewDirectiveRange.hasError.not()) {
            val viewResult = directiveResults[viewDirectiveRange.key]
            val isExpanded = viewResult?.collapsed == false
            ViewDirectiveInlineContent(
                viewVal = viewVal,
                displayText = viewDirectiveRange.displayText,
                directiveKey = viewDirectiveRange.key,
                sourceText = viewDirectiveRange.sourceText,
                textStyle = textStyle,
                isDirectiveExpanded = isExpanded,
                directiveError = viewResult?.error,
                directiveWarning = viewResult?.warning?.displayMessage,
                onNoteTap = { noteId, noteContent ->
                    onViewNoteTap?.invoke(viewDirectiveRange.key, noteId, noteContent)
                },
                onEditDirective = {
                    onViewEditDirective?.invoke(viewDirectiveRange.key, viewDirectiveRange.sourceText)
                        ?: onDirectiveTap?.invoke(viewDirectiveRange.key, viewDirectiveRange.sourceText)
                },
                onDirectiveRefresh = { newText ->
                    onViewDirectiveRefresh?.invoke(lineIndex, viewDirectiveRange.key, viewDirectiveRange.sourceText, newText)
                },
                onDirectiveConfirm = { newText ->
                    onViewDirectiveConfirm?.invoke(lineIndex, viewDirectiveRange.key, viewDirectiveRange.sourceText, newText)
                },
                onDirectiveCancel = {
                    onViewDirectiveCancel?.invoke(lineIndex, viewDirectiveRange.key, viewDirectiveRange.sourceText)
                }
            )
        } else if (buttonDirectiveRange != null && buttonVal != null && buttonDirectiveRange.hasError.not()) {
            ButtonDirectiveInlineContent(
                buttonVal = buttonVal,
                directiveKey = buttonDirectiveRange.key,
                sourceText = buttonDirectiveRange.sourceText,
                executionState = buttonExecutionStates[buttonDirectiveRange.key] ?: ButtonExecutionState.IDLE,
                errorMessage = buttonErrors[buttonDirectiveRange.key],
                onButtonClick = {
                    onButtonClick?.invoke(buttonDirectiveRange.key, buttonVal, buttonDirectiveRange.sourceText)
                },
                onEditDirective = {
                    onDirectiveTap?.invoke(buttonDirectiveRange.key, buttonDirectiveRange.sourceText)
                }
            )
        } else {
            DirectiveOverlayText(
                displayResult = displayResult,
                content = content,
                lineIndex = lineIndex,
                textStyle = textStyle,
                isFocused = isFocused,
                hasExternalSelection = hasExternalSelection,
                displayCursor = displayCursor,
                cursorInDirective = cursorInDirective,
                cursorAlpha = cursorAlpha,
                onDirectiveTap = onDirectiveTap,
                onTextLayout = { layoutResult ->
                    textLayoutResultState = layoutResult
                    onTextLayoutResult(layoutResult)
                },
                onTapAtSourcePosition = { sourcePosition, tapOffset ->
                    if (onTapInsideSelection != null
                        && controller.isContentOffsetInSelection(lineIndex, sourcePosition)) {
                        onTapInsideSelection(tapOffset)
                    } else {
                        onTapStarting?.invoke()
                        controller.setContentCursor(lineIndex, sourcePosition)
                    }
                },
                onSymbolTap = onSymbolTap,
                symbolOverlays = symbolOverlays
            )
        }
    }
}
