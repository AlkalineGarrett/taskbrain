package org.alkaline.taskbrain.ui.currentnote.ime

import android.view.View
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.alkaline.taskbrain.R
import org.alkaline.taskbrain.data.Note
import org.alkaline.taskbrain.dsl.directives.DirectiveResult
import org.alkaline.taskbrain.dsl.directives.DirectiveSegment
import org.alkaline.taskbrain.dsl.directives.DirectiveSegmenter
import org.alkaline.taskbrain.dsl.directives.DisplayTextResult
import org.alkaline.taskbrain.dsl.runtime.values.ButtonVal
import org.alkaline.taskbrain.dsl.runtime.values.ViewVal
import org.alkaline.taskbrain.dsl.ui.ButtonExecutionState
import org.alkaline.taskbrain.dsl.ui.DirectiveEditRow
import org.alkaline.taskbrain.dsl.ui.DirectiveTextInput
import org.alkaline.taskbrain.ui.currentnote.rendering.ButtonCallbacks
import org.alkaline.taskbrain.ui.currentnote.rendering.ControlledLineView
import org.alkaline.taskbrain.ui.currentnote.rendering.DirectiveCallbacks
import org.alkaline.taskbrain.ui.currentnote.gestures.LineLayoutInfo
import org.alkaline.taskbrain.ui.currentnote.selection.GutterSelectionState
import org.alkaline.taskbrain.ui.currentnote.selection.rememberGutterSelectionState
import androidx.compose.runtime.mutableStateListOf
import org.alkaline.taskbrain.ui.currentnote.EditorController
import org.alkaline.taskbrain.ui.currentnote.EditorId
import org.alkaline.taskbrain.ui.currentnote.EditorState
import org.alkaline.taskbrain.ui.currentnote.LocalInlineEditState
import org.alkaline.taskbrain.ui.currentnote.LocalSelectionCoordinator
import org.alkaline.taskbrain.ui.currentnote.InlineEditSession
import org.alkaline.taskbrain.ui.currentnote.util.SymbolOverlay
import org.alkaline.taskbrain.ui.currentnote.util.drawSymbolOverlays
import org.alkaline.taskbrain.ui.currentnote.util.hasVisibleBadges
import org.alkaline.taskbrain.ui.currentnote.util.TappableSymbol


/**
 * Finds the tappable symbol character index at or adjacent to a tap position.
 * Handles the case where emoji renders wider than its layout slot, so the tap
 * may resolve to the next character — checks the previous char's bounding box.
 *
 * @param content The source text to check against
 * @param position The resolved character position from the tap
 * @param getBoundingBox Returns the bounding box for a character at the given index
 * @param tapX The raw x-coordinate of the tap
 */
private fun findTappedSymbol(
    content: String,
    position: Int,
    getBoundingBox: (Int) -> Rect,
    tapX: Float
): Int? = when {
    TappableSymbol.at(content, position) != null -> position
    position > 0 && TappableSymbol.at(content, position - 1) != null
        && tapX <= getBoundingBox(position - 1).right -> position - 1
    else -> null
}

// Directive box colors
private val DirectiveErrorColor = Color(0xFFF44336)    // Red
private val DirectiveWarningColor = Color(0xFFFF9800)  // Orange
private val DirectiveSuccessColor = Color(0xFF4CAF50)  // Green

// View directive colors
private val ViewIndicatorColor = Color(0xFFB0BEC5)   // Blue-gray (left border for views)
private val ViewDividerColor = Color(0xFF9C27B0)     // Purple (divider between viewed notes)

// Dashed box drawing parameters
private object DirectiveBoxStyle {
    val strokeWidth = 1.dp
    val dashLength = 4.dp
    val gapLength = 2.dp
    val cornerRadius = 3.dp
    val padding = 2.dp
}

// Cursor drawing
private val CursorColor = Color.Black
private val CursorWidth = 2.dp

// Empty directive placeholder
private val EmptyDirectiveTapWidth = 16.dp

// View directive layout
private val ViewEditButtonSize = 24.dp
private val ViewEditIconSize = 16.dp

// Button directive layout
private val ButtonMinHeight = 32.dp
private val ButtonCornerRadius = 4.dp
private val ButtonIconSize = 16.dp
private val ButtonBackground = Color(0xFF2196F3)  // Blue
private val ButtonLoadingBackground = Color(0xFF90CAF9)  // Light blue
private val ButtonSuccessBackground = Color(0xFF4CAF50)  // Green
private val ButtonErrorBackground = Color(0xFFF44336)  // Red
private val ButtonContentColor = Color.White

/**
 * A directive-aware text input that allows editing around directives.
 *
 * Key features:
 * - Shows computed directive results inline (replacing source text visually)
 * - Directive results are displayed in dashed boxes and are tappable
 * - Non-directive text is fully editable
 * - Cursor position maps correctly between source and display
 * - View directives show note content with inline editing support
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
    modifier: Modifier = Modifier
) {
    // Unpack callback bundles for internal use
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

    // Build display text with directive results
    val displayResult = remember(content, directiveResults) {
        DirectiveSegmenter.buildDisplayText(content, directiveResults)
    }

    // Map source cursor to display cursor
    val displayCursor = remember(contentCursor, displayResult) {
        mapSourceToDisplayCursor(contentCursor, displayResult)
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

    // Check if cursor is inside a directive (shouldn't show cursor there)
    val cursorInDirective = remember(contentCursor, displayResult) {
        displayResult.directiveDisplayRanges.any { range ->
            contentCursor > range.sourceRange.first && contentCursor < range.sourceRange.last + 1
        }
    }

    // Check if this line has a view directive
    val viewDirectiveRange = remember(displayResult, directiveResults) {
        displayResult.directiveDisplayRanges.find { range -> range.isView }
    }

    // Get ViewVal if this is a view directive
    val viewVal = remember(viewDirectiveRange, directiveResults) {
        if (viewDirectiveRange != null) {
            val result = directiveResults[viewDirectiveRange.key]
            result?.toValue() as? ViewVal
        } else null
    }

    // Check if this line has a button directive
    val buttonDirectiveRange = remember(displayResult, directiveResults) {
        displayResult.directiveDisplayRanges.find { range -> range.isButton }
    }

    // Get ButtonVal if this is a button directive
    val buttonVal = remember(buttonDirectiveRange, directiveResults) {
        if (buttonDirectiveRange != null) {
            val result = directiveResults[buttonDirectiveRange.key]
            result?.toValue() as? ButtonVal
        } else null
    }

    Box(
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged { focusState -> onFocusChanged(focusState.isFocused) }
            .lineImeConnection(imeState, contentCursor, hostView)
            .focusable(interactionSource = interactionSource)
    ) {
        if (displayResult.directiveDisplayRanges.isEmpty()) {
            // No directives - render as simple text
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
                    .pointerInput(content, onSymbolTap, onTapStarting, controller) {
                        detectTapGestures { offset ->
                            textLayoutResultState?.let { layout ->
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
                                onTapStarting?.invoke()
                                controller.setContentCursor(lineIndex, position)
                            }
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
            // View directive with successful result - render with inline editing support
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
            // Button directive with successful result - render as clickable button
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
            // Has directives (non-view) - render with overlay boxes
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
                onTapAtSourcePosition = { sourcePosition ->
                    onTapStarting?.invoke()
                    controller.setContentCursor(lineIndex, sourcePosition)
                },
                onSymbolTap = onSymbolTap,
                symbolOverlays = symbolOverlays
            )
        }
    }
}

/**
 * Renders display text with directive boxes as overlays.
 * Uses a single BasicText for correct cursor positioning.
 */
@Composable
private fun DirectiveOverlayText(
    displayResult: DisplayTextResult,
    content: String,
    lineIndex: Int,
    textStyle: TextStyle,
    isFocused: Boolean,
    hasExternalSelection: Boolean,
    displayCursor: Int,
    cursorInDirective: Boolean,
    cursorAlpha: Float,
    onDirectiveTap: ((directiveKey: String, sourceText: String) -> Unit)?,
    onTextLayout: (TextLayoutResult) -> Unit,
    onTapAtSourcePosition: (Int) -> Unit,
    onSymbolTap: ((lineIndex: Int, charOffsetInLine: Int) -> Unit)? = null,
    symbolOverlays: List<SymbolOverlay> = emptyList()
) {
    var textLayoutResult: TextLayoutResult? by remember { mutableStateOf(null) }
    val hasOverlays = symbolOverlays.hasVisibleBadges()
    val overlayTextMeasurer = if (hasOverlays) rememberTextMeasurer() else null

    Box(modifier = Modifier.fillMaxWidth()) {
        // Render the full display text
        BasicText(
            text = displayResult.displayText,
            style = textStyle,
            onTextLayout = { layout ->
                textLayoutResult = layout
                onTextLayout(layout)
            },
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(displayResult, onSymbolTap, onTapAtSourcePosition) {
                    detectTapGestures { offset ->
                        textLayoutResult?.let { layout ->
                            val displayPosition = layout.getOffsetForPosition(offset)
                            val sourcePosition = mapDisplayToSourceCursor(displayPosition, displayResult)

                            // Check if tap landed on an alarm directive. Use display position
                            // for initial range check, then verify against the actual bounding
                            // box to avoid triggering when tapping empty space to the right
                            // of the alarm emoji (getOffsetForPosition clamps to text length).
                            val alarmDirective = displayResult.directiveDisplayRanges.find {
                                it.isAlarm && displayPosition >= it.displayRange.first
                                    && displayPosition <= it.displayRange.last + 1
                            }
                            if (alarmDirective != null && onSymbolTap != null) {
                                // Verify tap X is actually within the alarm emoji bounds
                                val alarmDisplayIdx = alarmDirective.displayRange.first
                                val alarmBounds = try {
                                    if (alarmDisplayIdx < layout.layoutInput.text.length)
                                        layout.getBoundingBox(alarmDisplayIdx)
                                    else null
                                } catch (_: Exception) { null }
                                val tapHitsAlarm = alarmBounds != null &&
                                    offset.x >= alarmBounds.left &&
                                    offset.x <= alarmBounds.right
                                if (tapHitsAlarm) {
                                    onSymbolTap(lineIndex, alarmDirective.sourceRange.first)
                                    return@detectTapGestures
                                }
                            }

                            val symbolIndex = findTappedSymbol(
                                content, sourcePosition,
                                getBoundingBox = { sourceIdx ->
                                    val dispIdx = mapSourceToDisplayCursor(sourceIdx, displayResult)
                                    if (dispIdx < layout.layoutInput.text.length) layout.getBoundingBox(dispIdx)
                                    else Rect.Zero
                                },
                                tapX = offset.x
                            )
                            if (symbolIndex != null && onSymbolTap != null) {
                                onSymbolTap(lineIndex, symbolIndex)
                            } else {
                                onTapAtSourcePosition(sourcePosition)
                            }
                        }
                    }
                }
                .drawWithContent {
                    drawContent()

                    // Guard against stale layout from previous render
                    val layout = textLayoutResult?.takeIf {
                        it.layoutInput.text.length == displayResult.displayText.length
                    } ?: return@drawWithContent

                    // Draw cursor
                    if (isFocused && !hasExternalSelection && !cursorInDirective) {
                        val cursorPos = displayCursor.coerceIn(0, displayResult.displayText.length)
                        val cursorRect = try {
                            layout.getCursorRect(cursorPos)
                        } catch (e: Exception) {
                            Rect(0f, 0f, CursorWidth.toPx(), layout.size.height.toFloat())
                        }
                        drawLine(
                            color = CursorColor.copy(alpha = cursorAlpha),
                            start = Offset(cursorRect.left, cursorRect.top),
                            end = Offset(cursorRect.left, cursorRect.bottom),
                            strokeWidth = CursorWidth.toPx()
                        )
                    }

                    // Draw dashed boxes around directive portions (skip alarm directives)
                    for (range in displayResult.directiveDisplayRanges) {
                        if (range.isAlarm) continue
                        val boxColor = when {
                            range.hasError -> DirectiveErrorColor
                            range.hasWarning -> DirectiveWarningColor
                            else -> DirectiveSuccessColor
                        }
                        val startOffset = range.displayRange.first.coerceIn(0, displayResult.displayText.length)
                        val endOffset = (range.displayRange.last + 1).coerceIn(0, displayResult.displayText.length)
                        val padding = DirectiveBoxStyle.padding.toPx()

                        if (startOffset < endOffset) {
                            // Non-empty display text - draw box around the text
                            val path = layout.getPathForRange(startOffset, endOffset)
                            val bounds = path.getBounds()

                            drawRoundRect(
                                color = boxColor,
                                topLeft = Offset(bounds.left - padding, bounds.top - padding),
                                size = Size(bounds.width + padding * 2, bounds.height + padding * 2),
                                cornerRadius = CornerRadius(DirectiveBoxStyle.cornerRadius.toPx()),
                                style = Stroke(
                                    width = DirectiveBoxStyle.strokeWidth.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(
                                        floatArrayOf(DirectiveBoxStyle.dashLength.toPx(), DirectiveBoxStyle.gapLength.toPx())
                                    )
                                )
                            )
                        } else if (range.displayText.isEmpty()) {
                            // Empty display text - draw a vertical dashed line placeholder
                            val cursorRect = try {
                                layout.getCursorRect(startOffset.coerceIn(0, displayResult.displayText.length))
                            } catch (e: Exception) {
                                Rect(0f, 0f, 2f, layout.size.height.toFloat())
                            }

                            // Draw vertical dashed line
                            drawLine(
                                color = boxColor,
                                start = Offset(cursorRect.left, cursorRect.top + padding),
                                end = Offset(cursorRect.left, cursorRect.bottom - padding),
                                strokeWidth = DirectiveBoxStyle.strokeWidth.toPx() * 1.5f,
                                pathEffect = PathEffect.dashPathEffect(
                                    floatArrayOf(DirectiveBoxStyle.dashLength.toPx() * 0.75f, DirectiveBoxStyle.gapLength.toPx())
                                )
                            )
                        }
                    }
                }
                .then(
                    if (hasOverlays && overlayTextMeasurer != null) {
                        Modifier.drawSymbolOverlays(
                            content = displayResult.displayText,
                            textLayoutResult = textLayoutResult,
                            overlays = symbolOverlays,
                            textMeasurer = overlayTextMeasurer
                        )
                    } else {
                        Modifier
                    }
                )
        )

        // Invisible tap targets for directives - wrapped in matchParentSize to not affect layout
        Box(modifier = Modifier.matchParentSize()) {
            val layout = textLayoutResult?.takeIf {
                it.layoutInput.text.length == displayResult.displayText.length
            } ?: return@Box

            for (range in displayResult.directiveDisplayRanges) {
                // Alarm directives are handled by the BasicText pointerInput above
                if (range.isAlarm) continue
                val startOffset = range.displayRange.first.coerceIn(0, displayResult.displayText.length)
                val endOffset = (range.displayRange.last + 1).coerceIn(0, displayResult.displayText.length)
                val padding = DirectiveBoxStyle.padding

                if (startOffset < endOffset) {
                    // Non-empty - use text bounds
                    val path = layout.getPathForRange(startOffset, endOffset)
                    val bounds = path.getBounds()

                    Box(
                        modifier = Modifier
                            .offset(
                                x = with(LocalDensity.current) { (bounds.left - padding.toPx()).toDp() },
                                y = with(LocalDensity.current) { (bounds.top - padding.toPx()).toDp() }
                            )
                            .size(
                                width = with(LocalDensity.current) { (bounds.width + padding.toPx() * 2).toDp() },
                                height = with(LocalDensity.current) { (bounds.height + padding.toPx() * 2).toDp() }
                            )
                            .clickable {
                                onDirectiveTap?.invoke(range.key, range.sourceText)
                            }
                    )
                } else if (range.displayText.isEmpty()) {
                    // Empty - create tap target at cursor position
                    val cursorRect = try {
                        layout.getCursorRect(startOffset.coerceIn(0, displayResult.displayText.length))
                    } catch (e: Exception) {
                        Rect(0f, 0f, 2f, layout.size.height.toFloat())
                    }

                    Box(
                        modifier = Modifier
                            .offset(
                                x = with(LocalDensity.current) { (cursorRect.left - EmptyDirectiveTapWidth.toPx() / 2).toDp() },
                                y = with(LocalDensity.current) { cursorRect.top.toDp() }
                            )
                            .size(
                                width = EmptyDirectiveTapWidth,
                                height = with(LocalDensity.current) { (cursorRect.bottom - cursorRect.top).toDp() }
                            )
                            .clickable {
                                onDirectiveTap?.invoke(range.key, range.sourceText)
                            }
                    )
                }
            }
        }
    }
}

/**
 * Modifier that draws a cursor at the specified position.
 */
/**
 * Draws a blinking cursor at the given position.
 *
 * [textLayoutResultProvider] is a lambda (not a direct value) so the TextLayoutResult
 * is read during the draw phase — after layout has measured the new text. Reading it
 * during composition would give a stale layout from the previous frame, causing the
 * cursor to flash at position 0 whenever getCursorRect throws for an out-of-range index.
 */
private fun Modifier.drawCursor(
    isFocused: Boolean,
    hasExternalSelection: Boolean,
    cursorPosition: Int,
    textLength: Int,
    textLayoutResultProvider: () -> TextLayoutResult?,
    cursorAlpha: Float
): Modifier = this.drawWithContent {
    drawContent()
    val textLayoutResult = textLayoutResultProvider()
    if (isFocused && !hasExternalSelection && textLayoutResult != null) {
        val cursorPos = cursorPosition.coerceIn(0, textLength)
        val cursorRect = try {
            textLayoutResult.getCursorRect(cursorPos)
        } catch (e: Exception) {
            Rect(0f, 0f, CursorWidth.toPx(), textLayoutResult.size.height.toFloat())
        }
        drawLine(
            color = CursorColor.copy(alpha = cursorAlpha),
            start = Offset(cursorRect.left, cursorRect.top),
            end = Offset(cursorRect.left, cursorRect.bottom),
            strokeWidth = CursorWidth.toPx()
        )
    }
}

/**
 * Maps a cursor position from source text to display text.
 */
private fun mapSourceToDisplayCursor(sourceCursor: Int, displayResult: DisplayTextResult): Int {
    if (displayResult.directiveDisplayRanges.isEmpty()) {
        return sourceCursor
    }

    var displayCursor = sourceCursor
    var adjustment = 0

    for (range in displayResult.directiveDisplayRanges) {
        if (sourceCursor <= range.sourceRange.first) {
            // Cursor is before this directive
            break
        } else if (sourceCursor > range.sourceRange.last) {
            // Cursor is after this directive - adjust for the length difference
            val sourceLength = range.sourceRange.last - range.sourceRange.first + 1
            val displayLength = range.displayRange.last - range.displayRange.first + 1
            adjustment += displayLength - sourceLength
        } else {
            // Cursor is inside the directive - place at end of directive display
            return range.displayRange.last + 1
        }
    }

    return (sourceCursor + adjustment).coerceAtLeast(0)
}

/**
 * Maps a cursor position from display text to source text.
 * This is the reverse of mapSourceToDisplayCursor.
 */
private fun mapDisplayToSourceCursor(displayCursor: Int, displayResult: DisplayTextResult): Int {
    if (displayResult.directiveDisplayRanges.isEmpty()) {
        return displayCursor
    }

    var sourceCursor = displayCursor

    for (range in displayResult.directiveDisplayRanges) {
        if (displayCursor <= range.displayRange.first) {
            // Cursor is before this directive - no adjustment needed
            break
        } else if (displayCursor > range.displayRange.last) {
            // Cursor is after this directive - adjust for the length difference
            val sourceLength = range.sourceRange.last - range.sourceRange.first + 1
            val displayLength = range.displayRange.last - range.displayRange.first + 1
            sourceCursor += sourceLength - displayLength
        } else {
            // Cursor is inside the directive display - map to end of source directive
            return range.sourceRange.last + 1
        }
    }

    return sourceCursor.coerceAtLeast(0)
}

// =============================================================================
// View Directive Inline Content
// =============================================================================

/**
 * Renders multi-line content by replacing directive source text with computed results.
 *
 * @param content The raw content (may contain directive source like [add(1,1)])
 * @param directiveResults Map of directive hash key to result
 * @return The rendered content with directives replaced by their display values
 */
internal fun renderContentWithDirectives(
    content: String,
    directiveResults: Map<String, DirectiveResult>
): String {
    if (directiveResults.isEmpty()) {
        return content
    }

    return content.lines().mapIndexed { lineIndex, lineContent ->
        // In inline editor context, use lineIndex as a synthetic ID since real noteIds aren't available
        val displayResult = DirectiveSegmenter.buildDisplayText(lineContent, directiveResults)
        displayResult.displayText
    }.joinToString("\n")
}

/**
 * Content from a view directive, rendered inline with inline editing support.
 * Shows the viewed notes' content with a subtle left border indicator.
 *
 * Features:
 * - Edit button at top-right to open directive editor overlay
 * - Each note section is independently editable inline
 * - Notes are separated by "---" dividers (non-editable)
 * - Saves on blur (when focus leaves the editable section)
 */
@Composable
private fun ViewDirectiveInlineContent(
    viewVal: ViewVal,
    displayText: String,
    directiveKey: String,
    sourceText: String,
    textStyle: TextStyle,
    isDirectiveExpanded: Boolean,
    directiveError: String?,
    directiveWarning: String?,
    onNoteTap: (noteId: String, noteContent: String) -> Unit,
    onEditDirective: () -> Unit,
    onDirectiveRefresh: ((newText: String) -> Unit)?,
    onDirectiveConfirm: ((newText: String) -> Unit)?,
    onDirectiveCancel: (() -> Unit)?
) {
    val notes = viewVal.notes
    val renderedContents = viewVal.renderedContents

    // Track which note is currently being edited (by index)
    var editingNoteIndex by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .viewIndicator(ViewIndicatorColor)
            .padding(start = 8.dp, top = 4.dp, bottom = 4.dp)
    ) {
        // Directive edit row at the TOP when expanded
        if (isDirectiveExpanded) {
            DirectiveEditRow(
                initialText = sourceText,
                textStyle = textStyle,
                errorMessage = directiveError,
                warningMessage = directiveWarning,
                onRefresh = { newText -> onDirectiveRefresh?.invoke(newText) },
                onConfirm = { newText -> onDirectiveConfirm?.invoke(newText) },
                onCancel = { onDirectiveCancel?.invoke() }
            )
        }

        // Main content box with edit/save buttons
        Box(modifier = Modifier.fillMaxWidth()) {
            val inlineEditState = LocalInlineEditState.current
            val activeSession = inlineEditState?.activeSession
            val editingNoteIsDirty = editingNoteIndex != null &&
                activeSession != null &&
                notes.getOrNull(editingNoteIndex ?: -1)?.id == activeSession.noteId &&
                activeSession.isDirty

            // Note content first (below buttons in z-order)
            if (notes.isEmpty()) {
                // Empty view - show placeholder
                Text(
                    text = displayText,
                    style = textStyle.copy(color = ViewIndicatorColor),
                    modifier = Modifier.fillMaxWidth()
                )
            } else if (notes.size == 1) {
                // Single note - simple case
                val note = notes.first()
                // Display rendered content, but edit raw content (preserves directives like [now])
                val displayContent = renderedContents?.firstOrNull() ?: note.content
                val editContent = note.content
                EditableViewNoteSection(
                    note = note,
                    displayContent = displayContent,
                    editContent = editContent,
                    textStyle = textStyle,
                    isEditing = editingNoteIndex == 0,
                    onStartEditing = { editingNoteIndex = 0 },
                    onSave = { newContent ->
                        editingNoteIndex = null
                        onNoteTap(note.id, newContent)
                    },
                    onCancel = { editingNoteIndex = null }
                )
            } else {
                // Multiple notes with separators
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    notes.forEachIndexed { index, note ->
                        // Separator before each note except first
                        if (index > 0) {
                            NoteSeparator()
                        }

                        // Note section - display rendered content, but edit raw content
                        val displayContent = renderedContents?.getOrNull(index) ?: note.content
                        val editContent = note.content
                        EditableViewNoteSection(
                            note = note,
                            displayContent = displayContent,
                            editContent = editContent,
                            textStyle = textStyle,
                            isEditing = editingNoteIndex == index,
                            onStartEditing = { editingNoteIndex = index },
                            onSave = { newContent ->
                                // Only clear editingNoteIndex if still pointing to this note
                                // (avoids overwriting when transitioning to another note)
                                if (editingNoteIndex == index) {
                                    editingNoteIndex = null
                                }
                                onNoteTap(note.id, newContent)
                            },
                            onCancel = {
                                if (editingNoteIndex == index) {
                                    editingNoteIndex = null
                                }
                            }
                        )
                    }
                }
            }

            // Overlay buttons on top (last child in Box = highest z-order = receives taps)
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (editingNoteIsDirty) {
                    Button(
                        onClick = {
                            activeSession?.let { onNoteTap(it.noteId, it.currentContent) }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colorResource(R.color.action_button_background),
                            contentColor = colorResource(R.color.action_button_text)
                        ),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(22.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_save),
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = colorResource(R.color.action_button_text)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.action_save),
                            fontSize = 11.sp
                        )
                    }
                }
                IconButton(
                    onClick = onEditDirective,
                    modifier = Modifier.size(ViewEditButtonSize)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Edit view directive",
                        tint = ViewIndicatorColor,
                        modifier = Modifier.size(ViewEditIconSize)
                    )
                }
            }
        }  // Close the Box
    }  // Close the Column
}

/**
 * A single note section within a view directive.
 * Supports inline editing - shows text normally, becomes editable when tapped.
 * Uses InlineEditState for full editor support (bullet, checkbox, indent commands work).
 *
 * @param displayContent The content to show when not editing (may be rendered with directives)
 * @param editContent The raw content to edit (preserves directives like [now])
 */
@Composable
private fun EditableViewNoteSection(
    note: Note,
    displayContent: String,
    editContent: String,
    textStyle: TextStyle,
    isEditing: Boolean,
    onStartEditing: () -> Unit,
    onSave: (newContent: String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hostView = LocalView.current
    val inlineEditState = LocalInlineEditState.current
    val selectionCoordinator = LocalSelectionCoordinator.current
    val focusRequester = remember { FocusRequester() }
    // Track if the text field has ever been focused - prevents canceling on initial render
    var hasBeenFocused by remember { mutableStateOf(false) }

    // Always use displayContent from the directive result's ViewVal.
    // Session content is only visible through its own EditorState text field,
    // never through this display path (prevents stale session content from
    // overriding fresh directive results — see Bug 10 in note-store-architecture.md).
    val effectiveDisplayContent = displayContent

    // Reset hasBeenFocused when exiting edit mode
    LaunchedEffect(isEditing) {
        if (!isEditing) {
            hasBeenFocused = false
        }
    }

    // Single session — always exists. Registered as active when editing starts.
    val session = remember(note.id, editContent) {
        val s = EditorState()
        s.updateFromText(editContent)
        val c = EditorController(s)
        InlineEditSession(
            noteId = note.id,
            originalContent = editContent,
            editorState = s,
            controller = c
        ).also { inlineEditState?.viewSessions?.set(note.id, it) }
    }

    // When editing starts, register the SAME session as active (no new session)
    remember(isEditing, note.id, session) {
        if (isEditing && inlineEditState != null) {
            if (inlineEditState.activeSession?.noteId != note.id) {
                inlineEditState.activateExistingSession(session)
            }
        }
        Unit
    }

    val isActiveSession = inlineEditState?.activeSession?.noteId == note.id

    Box(modifier = modifier.fillMaxWidth()) {
        InlineNoteEditor(
            session = session,
            autoFocus = isEditing && isActiveSession,
            textStyle = textStyle,
            focusRequester = focusRequester,
            hostView = hostView,
            onFocusChanged = { isFocused ->
                if (isFocused) {
                    selectionCoordinator?.activate(EditorId.View(note.id))
                    if (!isEditing) {
                        onStartEditing()
                    }
                    hasBeenFocused = true
                } else if (hasBeenFocused && isEditing && isActiveSession) {
                    val active = inlineEditState?.activeSession
                    val coordinator = selectionCoordinator
                    val hasExpandedDirective = active?.expandedDirectiveKey != null
                    if (!hasExpandedDirective && coordinator?.isFocusGuarded != true) {
                        if (active?.isDirty == true) {
                            onSave(active.currentContent)
                        } else {
                            inlineEditState?.endSession()
                            onCancel()
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        if (isEditing && isActiveSession) {
            LaunchedEffect(Unit) {
                try { focusRequester.requestFocus() } catch (_: Exception) {}
            }
            val active = inlineEditState?.activeSession
            if (active != null) {
                LaunchedEffect(active.isCollapsingDirective) {
                    if (active.isCollapsingDirective) {
                        kotlinx.coroutines.delay(500)
                        active.clearCollapsingFlag()
                    }
                }
            }
        }
    }
}


/**
 * Inline editor for a note within a view directive.
 * Uses the InlineEditSession's EditorState and EditorController for full editing support.
 * Commands from CommandBar will route to this controller when active.
 *
 * Uses focusGroup() to track focus at the editor level - focus loss is only reported
 * when focus leaves the entire editor, not when it moves between lines.
 *
 * Each line has its own FocusRequester and IME connection for proper line move support.
 *
 * Renders directives properly - collapsed by default, tappable to expand directive editor.
 * Shows DirectiveEditRow below lines with expanded directives.
 */
@Composable
private fun InlineNoteEditor(
    session: InlineEditSession,
    textStyle: TextStyle,
    focusRequester: FocusRequester,
    hostView: View,
    onFocusChanged: (Boolean) -> Unit,
    /** Whether to auto-focus on mount. False for display mode to prevent unwanted editing. */
    autoFocus: Boolean = true,
    modifier: Modifier = Modifier
) {
    val editorState = session.editorState
    val controller = session.controller
    val directiveResults = session.directiveResults
    val inlineEditState = LocalInlineEditState.current

    // Track expanded directive
    val expandedDirectiveKey = session.expandedDirectiveKey
    val expandedDirectiveSourceText = session.expandedDirectiveSourceText

    // Observe state version to trigger recomposition
    @Suppress("UNUSED_VARIABLE")
    val stateTrigger = editorState.stateVersion

    // Per-line focus requesters — incrementally grown/shrunk to avoid recreating all
    // requesters on line count change (which would detach focused elements and cause
    // transient focus loss that triggers unwanted save).
    val lineCount = editorState.lines.size
    val lineFocusRequesters = remember { mutableListOf<FocusRequester>() }
    while (lineFocusRequesters.size < lineCount) lineFocusRequesters.add(FocusRequester())
    while (lineFocusRequesters.size > lineCount) lineFocusRequesters.removeAt(lineFocusRequesters.lastIndex)

    // Track overall editor focus state (managed at focusGroup level)
    var isEditorFocused by remember { mutableStateOf(false) }
    // Track line count at last focus gain — if it changed when focus is lost,
    // it's a structural edit (backspace/enter), not the user leaving the editor.
    var lineCountAtFocusGain by remember { mutableIntStateOf(lineCount) }

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
    // Track the last-seen version to avoid stealing focus on session switches
    // (where stateVersion is already > 0 from a previous interaction).
    // Track the stateVersion at mount time to distinguish user taps (which increment it)
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

    // Request focus back when directive editor is closed
    LaunchedEffect(expandedDirectiveKey) {
        if (expandedDirectiveKey == null && session.isCollapsingDirective) {
            // Directive was collapsed, request focus back to the focused line
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

    // Handle initial focus (only when autoFocus is enabled — not in display mode)
    LaunchedEffect(autoFocus) {
        if (!autoFocus) return@LaunchedEffect
        try {
            if (editorState.focusedLineIndex in lineFocusRequesters.indices) {
                lineFocusRequesters[editorState.focusedLineIndex].requestFocus()
            }
        } catch (_: Exception) {
            // Focus request failed - ignore
        }
    }

    // Use focusGroup to track focus at editor level
    // Focus loss is only reported when focus leaves the ENTIRE group
    // AND we're not expecting focus on another line (from a tap)
    // Write layouts and gutter state to InlineEditState (keyed by noteId),
    // decoupled from the session so they survive session transitions.
    inlineEditState?.viewLineLayouts?.set(session.noteId, lineLayouts)
    inlineEditState?.viewGutterStates?.set(session.noteId, gutterSelectionState)

    Column(
        modifier = modifier
            .focusGroup()
            .onFocusChanged { focusState ->
                val wasFocused = isEditorFocused
                isEditorFocused = focusState.hasFocus
                if (focusState.hasFocus && !wasFocused) {
                    lineCountAtFocusGain = editorState.lines.size
                    onFocusChanged(true)
                } else if (!focusState.hasFocus && wasFocused) {
                    val lineCountChanged = editorState.lines.size != lineCountAtFocusGain
                    if (pendingFocusLineIndex >= 0 || lineCountChanged) {
                        // Focus transfer between lines or structural edit — ignore
                    } else {
                        onFocusChanged(false)
                    }
                }
            }
    ) {
        // Render each line using the shared ControlledLineView (same as the main editor),
        // giving inline editors selection overlays, prefix rendering, and directives for free.
        editorState.lines.forEachIndexed { index, lineState ->
            if (index < lineFocusRequesters.size) {
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

/**
 * Visual separator between notes in a multi-note view.
 * Renders a dashed purple horizontal line.
 */
@Composable
private fun NoteSeparator() {
    androidx.compose.foundation.Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        val dashLength = 4.dp.toPx()
        val gapLength = 3.dp.toPx()
        drawLine(
            color = ViewDividerColor,
            start = Offset(0f, 0f),
            end = Offset(size.width, 0f),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashLength, gapLength))
        )
    }
}

/**
 * Button directive rendered as an interactive button with settings icon.
 * Shows the button label and executes the action when clicked.
 * Displays error message below the button if execution failed.
 */
@Composable
private fun ButtonDirectiveInlineContent(
    buttonVal: ButtonVal,
    directiveKey: String,
    sourceText: String,
    executionState: ButtonExecutionState,
    errorMessage: String? = null,
    onButtonClick: () -> Unit,
    onEditDirective: () -> Unit
) {
    val backgroundColor = when (executionState) {
        ButtonExecutionState.IDLE -> ButtonBackground
        ButtonExecutionState.LOADING -> ButtonLoadingBackground
        ButtonExecutionState.SUCCESS -> ButtonSuccessBackground
        ButtonExecutionState.ERROR -> ButtonErrorBackground
    }

    val isEnabled = executionState != ButtonExecutionState.LOADING

    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The clickable button
            Box(
                modifier = Modifier
                    .height(ButtonMinHeight)
                    .background(
                        color = backgroundColor,
                        shape = RoundedCornerShape(ButtonCornerRadius)
                    )
                    .clickable(enabled = isEnabled) { onButtonClick() }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                when (executionState) {
                    ButtonExecutionState.LOADING -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(ButtonIconSize),
                            color = ButtonContentColor,
                            strokeWidth = 2.dp
                        )
                    }
                    else -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = ButtonContentColor,
                                modifier = Modifier.size(ButtonIconSize)
                            )
                            Text(
                                text = buttonVal.label,
                                color = ButtonContentColor,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }
                }
            }

            // Settings icon to edit the directive
            IconButton(
                onClick = onEditDirective,
                modifier = Modifier
                    .size(ViewEditButtonSize)
                    .padding(start = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Edit button directive",
                    tint = ViewIndicatorColor,
                    modifier = Modifier.size(ViewEditIconSize)
                )
            }
        }

        // Error message display
        if (errorMessage != null) {
            Text(
                text = errorMessage,
                color = ButtonErrorBackground,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/**
 * Modifier for view directive content - draws a left border indicator.
 * This provides a subtle visual distinction for viewed content.
 */
private fun Modifier.viewIndicator(color: Color): Modifier = this.drawBehind {
    val strokeWidth = 2.dp.toPx()

    // Draw solid left border
    drawLine(
        color = color,
        start = Offset(0f, 0f),
        end = Offset(0f, size.height),
        strokeWidth = strokeWidth
    )
}

