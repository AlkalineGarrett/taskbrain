package org.alkaline.taskbrain.ui.currentnote.ime

import android.util.Log
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import org.alkaline.taskbrain.ui.currentnote.EditorController
import org.alkaline.taskbrain.ui.currentnote.LocalInlineEditState
import org.alkaline.taskbrain.ui.currentnote.InlineEditSession

private const val TAG = "DirectiveAwareLineInput"

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
    onDirectiveTap: (directiveKey: String, sourceText: String) -> Unit,
    onViewNoteTap: ((directiveKey: String, noteId: String, noteContent: String) -> Unit)? = null,
    onViewEditDirective: ((directiveKey: String, sourceText: String) -> Unit)? = null,
    onViewDirectiveRefresh: ((lineIndex: Int, directiveKey: String, sourceText: String, newText: String) -> Unit)? = null,
    onViewDirectiveConfirm: ((lineIndex: Int, directiveKey: String, sourceText: String, newText: String) -> Unit)? = null,
    onViewDirectiveCancel: ((lineIndex: Int, directiveKey: String, sourceText: String) -> Unit)? = null,
    onButtonClick: ((directiveKey: String, buttonVal: ButtonVal, sourceText: String) -> Unit)? = null,
    buttonExecutionStates: Map<String, ButtonExecutionState> = emptyMap(),
    buttonErrors: Map<String, String> = emptyMap(),
    modifier: Modifier = Modifier
) {
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
    val displayResult = remember(content, lineIndex, directiveResults) {
        DirectiveSegmenter.buildDisplayText(content, lineIndex, directiveResults)
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
            BasicText(
                text = content,
                style = textStyle,
                onTextLayout = { layoutResult ->
                    textLayoutResultState = layoutResult
                    onTextLayoutResult(layoutResult)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .drawCursor(
                        isFocused = isFocused,
                        hasExternalSelection = hasExternalSelection,
                        cursorPosition = contentCursor,
                        textLength = content.length,
                        textLayoutResult = textLayoutResultState,
                        cursorAlpha = cursorAlpha
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
                        ?: onDirectiveTap(viewDirectiveRange.key, viewDirectiveRange.sourceText)
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
                    onDirectiveTap(buttonDirectiveRange.key, buttonDirectiveRange.sourceText)
                }
            )
        } else {
            // Has directives (non-view) - render with overlay boxes
            DirectiveOverlayText(
                displayResult = displayResult,
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
                    controller.setCursor(lineIndex, sourcePosition)
                }
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
    textStyle: TextStyle,
    isFocused: Boolean,
    hasExternalSelection: Boolean,
    displayCursor: Int,
    cursorInDirective: Boolean,
    cursorAlpha: Float,
    onDirectiveTap: (directiveKey: String, sourceText: String) -> Unit,
    onTextLayout: (TextLayoutResult) -> Unit,
    onTapAtSourcePosition: (Int) -> Unit
) {
    var textLayoutResult: TextLayoutResult? by remember { mutableStateOf(null) }

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
                .pointerInput(displayResult) {
                    detectTapGestures { offset ->
                        textLayoutResult?.let { layout ->
                            // Get the display position from tap coordinates
                            val displayPosition = layout.getOffsetForPosition(offset)
                            // Map to source position and notify
                            val sourcePosition = mapDisplayToSourceCursor(displayPosition, displayResult)
                            onTapAtSourcePosition(sourcePosition)
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

                    // Draw dashed boxes around directive portions
                    for (range in displayResult.directiveDisplayRanges) {
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
        )

        // Invisible tap targets for directives - wrapped in matchParentSize to not affect layout
        Box(modifier = Modifier.matchParentSize()) {
            val layout = textLayoutResult?.takeIf {
                it.layoutInput.text.length == displayResult.displayText.length
            } ?: return@Box

            for (range in displayResult.directiveDisplayRanges) {
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
                                onDirectiveTap(range.key, range.sourceText)
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
                                onDirectiveTap(range.key, range.sourceText)
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
private fun Modifier.drawCursor(
    isFocused: Boolean,
    hasExternalSelection: Boolean,
    cursorPosition: Int,
    textLength: Int,
    textLayoutResult: TextLayoutResult?,
    cursorAlpha: Float
): Modifier = this.drawWithContent {
    drawContent()
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
 * @param directiveResults Map of directive key ("lineIndex:startOffset") to result
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
        val displayResult = DirectiveSegmenter.buildDisplayText(lineContent, lineIndex, directiveResults)
        displayResult.displayText
    }.joinToString("\n")
}

/**
 * Determines the effective display content during the transitional state.
 *
 * When saving an inline edit, there's a window between when isEditing becomes false
 * and when directiveResults is refreshed with new content. During this window,
 * displayContent (from directiveResults) contains STALE content.
 *
 * This function returns rendered sessionContent during the transitional state to ensure
 * the user sees their edits immediately with directives properly rendered.
 *
 * @param isEditing Whether the note is currently in edit mode
 * @param hasActiveSession Whether there's an active inline edit session for this note
 * @param displayContent The content from directiveResults (may be stale during transition)
 * @param sessionContent The current raw content from the active session (always fresh)
 * @param sessionDirectiveResults The directive results from the session (for rendering)
 * @return The content to display (with directives rendered)
 */
internal fun getEffectiveDisplayContent(
    isEditing: Boolean,
    hasActiveSession: Boolean,
    displayContent: String,
    sessionContent: String?,
    sessionDirectiveResults: Map<String, DirectiveResult>?
): String {
    return if (!isEditing && hasActiveSession && sessionContent != null) {
        // Transitional state: session still active after save
        // Render the fresh content using the session's directive results
        if (sessionDirectiveResults != null && sessionDirectiveResults.isNotEmpty()) {
            renderContentWithDirectives(sessionContent, sessionDirectiveResults)
        } else {
            sessionContent
        }
    } else {
        displayContent
    }
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

    Log.d(TAG, "ViewDirectiveInlineContent: notes.size=${notes.size}, renderedContents.size=${renderedContents?.size}")
    notes.forEachIndexed { index, note ->
        val displayContent = renderedContents?.getOrNull(index) ?: note.content
        Log.d(TAG, "  Note[$index]: id=${note.id}, displayContentPreview='${displayContent.take(50)}...', rawContentPreview='${note.content.take(50)}...'")
    }

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

        // Main content box with edit button
        Box(modifier = Modifier.fillMaxWidth()) {
            // Edit directive button at top-right
            IconButton(
                onClick = onEditDirective,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(ViewEditButtonSize)
                    .padding(end = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Edit view directive",
                    tint = ViewIndicatorColor,
                    modifier = Modifier.size(ViewEditIconSize)
                )
            }

            // Note content - either split by sections or as a single block
            if (notes.isEmpty()) {
                // Empty view - show placeholder
                Text(
                    text = displayText,
                    style = textStyle.copy(color = ViewIndicatorColor),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = ViewEditButtonSize)
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
                    onCancel = { editingNoteIndex = null },
                    modifier = Modifier.padding(end = ViewEditButtonSize)
                )
            } else {
                // Multiple notes with separators
                Log.d(TAG, "ViewDirectiveInlineContent: Rendering ${notes.size} notes in Column")
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = ViewEditButtonSize)
                ) {
                    notes.forEachIndexed { index, note ->
                        // Separator before each note except first
                        if (index > 0) {
                            Log.d(TAG, "  Rendering separator before note[$index]")
                            NoteSeparator()
                        }

                        // Note section - display rendered content, but edit raw content
                        val displayContent = renderedContents?.getOrNull(index) ?: note.content
                        val editContent = note.content
                        Log.d(TAG, "  Rendering EditableViewNoteSection[$index]: displayContentLength=${displayContent.length}, editContentLength=${editContent.length}")
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
    val focusRequester = remember { FocusRequester() }
    // Track if the text field has ever been focused - prevents canceling on initial render
    var hasBeenFocused by remember { mutableStateOf(false) }

    // Use helper function to get correct content during transitional state
    val hasActiveSession = inlineEditState?.isEditingNote(note.id) == true
    val activeSession = inlineEditState?.activeSession
    val effectiveDisplayContent = getEffectiveDisplayContent(
        isEditing = isEditing,
        hasActiveSession = hasActiveSession,
        displayContent = displayContent,
        sessionContent = activeSession?.currentContent,
        sessionDirectiveResults = activeSession?.directiveResults
    )

    Log.d(TAG, "EditableViewNoteSection: noteId=${note.id}, isEditing=$isEditing, effectiveDisplayContentLength=${effectiveDisplayContent.length}, preview='${effectiveDisplayContent.take(30)}...'")

    // Reset hasBeenFocused when exiting edit mode
    LaunchedEffect(isEditing) {
        if (!isEditing) {
            hasBeenFocused = false
        }
    }

    // Start/end inline edit session based on editing state
    // Use editContent (raw) for editing, not displayContent (rendered)
    LaunchedEffect(isEditing, note.id, editContent) {
        if (isEditing && inlineEditState != null && !inlineEditState.isEditingNote(note.id)) {
            inlineEditState.startSession(note.id, editContent)
        }
    }

    // Get the active session if this note is being edited
    val session = if (isEditing) inlineEditState?.activeSession?.takeIf { it.noteId == note.id } else null

    Box(
        modifier = modifier.fillMaxWidth()
    ) {
        if (isEditing && session != null) {
            // Editable mode - use InlineNoteEditor with proper EditorController support
            InlineNoteEditor(
                session = session,
                textStyle = textStyle,
                focusRequester = focusRequester,
                hostView = hostView,
                onFocusChanged = { isFocused ->
                    if (isFocused) {
                        hasBeenFocused = true
                        // Don't clear the collapsing flag immediately - it will be cleared
                        // after a short delay via LaunchedEffect to handle rapid focus changes
                    } else if (hasBeenFocused && isEditing) {
                        // Check if focus moved to an expanded directive editor (part of inline editing)
                        // or if we're in the process of collapsing a directive (focus will return)
                        // or if a move operation is in progress (focus will return)
                        val hasExpandedDirective = session.expandedDirectiveKey != null
                        val isCollapsing = session.isCollapsingDirective
                        val isMoving = session.isMoveInProgress
                        if (!hasExpandedDirective && !isCollapsing && !isMoving) {
                            // Only handle focus loss if we previously had focus and no directive interaction
                            // DON'T end the UI session here - let the save/refresh flow handle it
                            // to avoid showing stale directiveResults during the async refresh.
                            // The session will be ended in CurrentNoteScreen after forceRefreshAllDirectives completes.
                            if (session.isDirty) {
                                onSave(session.currentContent)
                            } else {
                                // For cancel (no changes), we can end the session immediately since no refresh needed
                                inlineEditState?.endSession()
                                onCancel()
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            // Request focus after the editor is composed
            LaunchedEffect(Unit) {
                try {
                    focusRequester.requestFocus()
                } catch (_: Exception) {
                    // Focus request failed - ignore
                }
            }

            // Clear the collapsing flag after a delay when focus is gained
            // This delay allows the directive confirm save chain to complete
            // before we allow focus loss to trigger exit-edit-mode
            LaunchedEffect(session.isCollapsingDirective) {
                if (session.isCollapsingDirective) {
                    // Wait for things to settle before clearing the flag
                    kotlinx.coroutines.delay(500)
                    session.clearCollapsingFlag()
                }
            }
        } else {
            // Display mode - uses effectiveDisplayContent which handles transitional state
            Log.d(TAG, "EditableViewNoteSection DISPLAY MODE: noteId=${note.id}, textLength=${effectiveDisplayContent.length}")
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onStartEditing)
            ) {
                SelectionContainer {
                    Text(
                        text = effectiveDisplayContent,
                        style = textStyle,
                        modifier = Modifier.fillMaxWidth()
                    )
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

    // Create per-line focus requesters
    val lineCount = editorState.lines.size
    val lineFocusRequesters = remember(lineCount) { List(lineCount) { FocusRequester() } }

    // Track overall editor focus state (managed at focusGroup level)
    var isEditorFocused by remember { mutableStateOf(false) }

    // Track which line is expecting focus from a tap (to prevent false focus loss)
    var pendingFocusLineIndex by remember { mutableIntStateOf(-1) }

    // Request focus on the focused line when focusedLineIndex changes
    LaunchedEffect(editorState.focusedLineIndex, editorState.stateVersion) {
        if (editorState.stateVersion > 0 && editorState.focusedLineIndex in lineFocusRequesters.indices) {
            try {
                lineFocusRequesters[editorState.focusedLineIndex].requestFocus()
            } catch (_: Exception) {
                // Focus request failed - ignore
            }
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

    // Also handle external focus requester for initial focus
    LaunchedEffect(Unit) {
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
    Column(
        modifier = modifier
            .focusGroup()
            .onFocusChanged { focusState ->
                val wasFocused = isEditorFocused
                isEditorFocused = focusState.hasFocus
                if (focusState.hasFocus && !wasFocused) {
                    // Clear move-in-progress flag now that focus is restored
                    session.isMoveInProgress = false
                    onFocusChanged(true)
                } else if (!focusState.hasFocus && wasFocused) {
                    // Check if we're expecting focus on another line (tap in progress)
                    if (pendingFocusLineIndex >= 0) {
                        // Don't report focus loss - we're moving between lines
                    } else {
                        // Focus left the entire editor group
                        onFocusChanged(false)
                    }
                }
            }
    ) {
        // Render each line from the EditorState with its own IME connection
        editorState.lines.forEachIndexed { index, lineState ->
            if (index < lineFocusRequesters.size) {
                InlineEditorLineWithIme(
                    lineIndex = index,
                    lineState = lineState,
                    controller = controller,
                    textStyle = textStyle,
                    focusRequester = lineFocusRequesters[index],
                    hostView = hostView,
                    isFocused = isEditorFocused && index == editorState.focusedLineIndex,
                    directiveResults = directiveResults,
                    onTapStarting = {
                        // Mark that we're expecting focus on this line
                        pendingFocusLineIndex = index
                    },
                    onLineFocused = {
                        // A line gained focus - clear pending and update controller
                        pendingFocusLineIndex = -1
                        controller.focusLine(index)
                    },
                    onDirectiveTap = { directiveKey, sourceText ->
                        inlineEditState?.toggleDirectiveExpanded(directiveKey, sourceText)
                    }
                )

                // Check if there's an expanded directive on this line
                val expandedOnThisLine = expandedDirectiveKey?.startsWith("$index:") == true
                if (expandedOnThisLine && expandedDirectiveSourceText != null) {
                    // Get the result for error/warning messages
                    val result = directiveResults[expandedDirectiveKey]
                    val errorMessage = result?.error
                    val warningMessage = result?.warning?.displayMessage

                    DirectiveEditRow(
                        initialText = expandedDirectiveSourceText,
                        textStyle = textStyle,
                        errorMessage = errorMessage,
                        warningMessage = warningMessage,
                        onRefresh = { currentText ->
                            // Re-execute and update result
                            inlineEditState?.refreshDirective(expandedDirectiveKey, currentText)
                        },
                        onConfirm = { newText ->
                            // Confirm the edit
                            inlineEditState?.confirmDirective(
                                index,
                                expandedDirectiveKey,
                                expandedDirectiveSourceText,
                                newText
                            )
                        },
                        onCancel = {
                            // Just collapse without saving changes
                            inlineEditState?.collapseDirective()
                        }
                    )
                }
            }
        }
    }
}

/**
 * A single line in the inline editor with its own IME connection.
 * Each line is independently focusable and handles its own keyboard input.
 *
 * Focus tracking is handled at the focusGroup level in InlineNoteEditor.
 * This component only reports when it gains focus via onLineFocused.
 */
@Composable
private fun InlineEditorLineWithIme(
    lineIndex: Int,
    lineState: org.alkaline.taskbrain.ui.currentnote.LineState,
    controller: EditorController,
    textStyle: TextStyle,
    focusRequester: FocusRequester,
    hostView: View,
    isFocused: Boolean,
    directiveResults: Map<String, DirectiveResult> = emptyMap(),
    onTapStarting: () -> Unit,
    onLineFocused: () -> Unit,
    onDirectiveTap: ((directiveKey: String, sourceText: String) -> Unit)? = null
) {
    // Create IME state for this line
    val imeState = remember(lineIndex, controller) {
        LineImeState(lineIndex, controller)
    }

    // Sync IME state when content changes
    val content = lineState.content
    val cursorPosition = lineState.contentCursorPosition
    LaunchedEffect(content, cursorPosition) {
        if (!imeState.isInBatchEdit) {
            imeState.syncFromController()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged { focusState ->
                if (focusState.isFocused) {
                    onLineFocused()
                }
                // Focus loss is handled at the focusGroup level in InlineNoteEditor
            }
            .lineImeConnection(imeState, cursorPosition, hostView)
            .focusable()
    ) {
        // Render the line content
        InlineEditorLine(
            lineIndex = lineIndex,
            lineState = lineState,
            controller = controller,
            textStyle = textStyle,
            isFocused = isFocused,
            directiveResults = directiveResults,
            onDirectiveTap = onDirectiveTap,
            onTapStarting = onTapStarting,
            onContentTap = {
                // Request focus on tap
                try {
                    focusRequester.requestFocus()
                } catch (_: Exception) {
                    // Focus request failed
                }
            }
        )
    }
}

/**
 * A single line in the inline editor.
 * Supports directive rendering - shows computed results in dashed boxes.
 */
@Composable
private fun InlineEditorLine(
    lineIndex: Int,
    lineState: org.alkaline.taskbrain.ui.currentnote.LineState,
    controller: EditorController,
    textStyle: TextStyle,
    isFocused: Boolean,
    directiveResults: Map<String, DirectiveResult> = emptyMap(),
    onDirectiveTap: ((directiveKey: String, sourceText: String) -> Unit)? = null,
    onTapStarting: (() -> Unit)? = null,
    onContentTap: (() -> Unit)? = null
) {
    val prefix = lineState.prefix
    val content = lineState.content
    val cursorPosition = lineState.contentCursorPosition
    val interactionSource = remember { MutableInteractionSource() }

    // Check if prefix contains a checkbox (for tap handling)
    val hasCheckbox = remember(prefix) {
        org.alkaline.taskbrain.ui.currentnote.util.LinePrefixes.hasCheckbox(prefix)
    }

    // Build display text with directive results for this line.
    // Key adjustment: parseAllDirectiveLocations uses full text (with prefix), so offsets
    // are relative to full text. We need to adjust keys when looking up results.
    val prefixLength = prefix.length
    val adjustedResults = remember(directiveResults, lineIndex, prefixLength) {
        if (prefixLength == 0) {
            directiveResults
        } else {
            // Adjust keys: if result is at "lineIndex:fullOffset", we need to look it up
            // when buildDisplayText creates key "lineIndex:contentOffset"
            // contentOffset = fullOffset - prefixLength
            directiveResults.mapKeys { (key, _) ->
                val parts = key.split(":")
                if (parts.size == 2 && parts[0] == lineIndex.toString()) {
                    val fullOffset = parts[1].toIntOrNull() ?: return@mapKeys key
                    val contentOffset = fullOffset - prefixLength
                    if (contentOffset >= 0) "$lineIndex:$contentOffset" else key
                } else {
                    key
                }
            }
        }
    }

    val displayResult = remember(content, lineIndex, adjustedResults) {
        DirectiveSegmenter.buildDisplayText(content, lineIndex, adjustedResults)
    }

    // Map source cursor to display cursor
    val displayCursor = remember(cursorPosition, displayResult) {
        mapSourceToDisplayCursor(cursorPosition, displayResult)
    }

    // Check if cursor is inside a directive
    val cursorInDirective = remember(cursorPosition, displayResult) {
        displayResult.directiveDisplayRanges.any { range ->
            cursorPosition > range.sourceRange.first && cursorPosition < range.sourceRange.last + 1
        }
    }

    // Cursor blinking animation
    val infiniteTransition = rememberInfiniteTransition(label = "inlineCursorBlink")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "inlineCursorAlpha"
    )

    var textLayoutResult: TextLayoutResult? by remember { mutableStateOf(null) }

    Row(modifier = Modifier.fillMaxWidth()) {
        // Prefix area - tappable if contains checkbox
        if (prefix.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .then(
                        if (hasCheckbox) {
                            Modifier.clickable(
                                interactionSource = interactionSource,
                                indication = null
                            ) {
                                controller.toggleCheckboxOnLine(lineIndex)
                            }
                        } else {
                            Modifier
                        }
                    )
            ) {
                BasicText(
                    text = prefix,
                    style = textStyle
                )
            }
        }

        // Content area - with directive rendering support
        Box(modifier = Modifier.weight(1f)) {
            if (displayResult.directiveDisplayRanges.isEmpty()) {
                // No directives - simple text rendering
                BasicText(
                    text = content.ifEmpty { " " },
                    style = textStyle,
                    onTextLayout = { textLayoutResult = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                // Notify that tap is starting BEFORE any focus changes
                                onTapStarting?.invoke()
                                textLayoutResult?.let { layout ->
                                    val charPos = layout.getOffsetForPosition(offset)
                                    controller.setCursor(lineIndex, charPos)
                                }
                                onContentTap?.invoke()
                            }
                        }
                        .drawWithContent {
                            drawContent()
                            if (isFocused && textLayoutResult != null) {
                                val cursorPos = cursorPosition.coerceIn(0, content.length)
                                val cursorRect = try {
                                    textLayoutResult!!.getCursorRect(cursorPos)
                                } catch (e: Exception) {
                                    Rect(0f, 0f, CursorWidth.toPx(), size.height)
                                }
                                drawLine(
                                    color = CursorColor.copy(alpha = cursorAlpha),
                                    start = Offset(cursorRect.left, cursorRect.top),
                                    end = Offset(cursorRect.left, cursorRect.bottom),
                                    strokeWidth = CursorWidth.toPx()
                                )
                            }
                        }
                )
            } else {
                // Has directives - render with overlay boxes
                InlineDirectiveOverlayText(
                    displayResult = displayResult,
                    textStyle = textStyle,
                    isFocused = isFocused,
                    displayCursor = displayCursor,
                    cursorInDirective = cursorInDirective,
                    cursorAlpha = cursorAlpha,
                    lineIndex = lineIndex,
                    controller = controller,
                    onDirectiveTap = onDirectiveTap,
                    onTextLayout = { textLayoutResult = it },
                    onTapStarting = onTapStarting,
                    onContentTap = onContentTap
                )
            }
        }
    }
}

/**
 * Renders line content with directive boxes for inline editor.
 * Similar to DirectiveOverlayText but adapted for inline editing context.
 */
@Composable
private fun InlineDirectiveOverlayText(
    displayResult: DisplayTextResult,
    textStyle: TextStyle,
    isFocused: Boolean,
    displayCursor: Int,
    cursorInDirective: Boolean,
    cursorAlpha: Float,
    lineIndex: Int,
    controller: EditorController,
    onDirectiveTap: ((directiveKey: String, sourceText: String) -> Unit)?,
    onTextLayout: (TextLayoutResult) -> Unit,
    onTapStarting: (() -> Unit)? = null,
    onContentTap: (() -> Unit)? = null
) {
    var textLayoutResult: TextLayoutResult? by remember { mutableStateOf(null) }

    Box(modifier = Modifier.fillMaxWidth()) {
        // Render the full display text
        BasicText(
            text = displayResult.displayText.ifEmpty { " " },
            style = textStyle,
            onTextLayout = { layout ->
                textLayoutResult = layout
                onTextLayout(layout)
            },
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(displayResult) {
                    detectTapGestures { offset ->
                        // Notify that tap is starting BEFORE any focus changes
                        onTapStarting?.invoke()
                        textLayoutResult?.let { layout ->
                            val displayPosition = layout.getOffsetForPosition(offset)
                            val sourcePosition = mapDisplayToSourceCursor(displayPosition, displayResult)
                            controller.setCursor(lineIndex, sourcePosition)
                        }
                        onContentTap?.invoke()
                    }
                }
                .drawWithContent {
                    drawContent()

                    val layout = textLayoutResult?.takeIf {
                        it.layoutInput.text.length == displayResult.displayText.length ||
                                (displayResult.displayText.isEmpty() && it.layoutInput.text.length == 1)
                    } ?: return@drawWithContent

                    // Draw cursor
                    if (isFocused && !cursorInDirective) {
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

                    // Draw dashed boxes around directive portions
                    for (range in displayResult.directiveDisplayRanges) {
                        val boxColor = when {
                            range.hasError -> DirectiveErrorColor
                            range.hasWarning -> DirectiveWarningColor
                            else -> DirectiveSuccessColor
                        }
                        val startOffset = range.displayRange.first.coerceIn(0, displayResult.displayText.length)
                        val endOffset = (range.displayRange.last + 1).coerceIn(0, displayResult.displayText.length)
                        val padding = DirectiveBoxStyle.padding.toPx()

                        if (startOffset < endOffset) {
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
                            val cursorRect = try {
                                layout.getCursorRect(startOffset.coerceIn(0, displayResult.displayText.length))
                            } catch (e: Exception) {
                                Rect(0f, 0f, 2f, layout.size.height.toFloat())
                            }
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
        )

        // Invisible tap targets for directives
        if (onDirectiveTap != null) {
            Box(modifier = Modifier.matchParentSize()) {
                val layout = textLayoutResult?.takeIf {
                    it.layoutInput.text.length == displayResult.displayText.length ||
                            (displayResult.displayText.isEmpty() && it.layoutInput.text.length == 1)
                } ?: return@Box

                for (range in displayResult.directiveDisplayRanges) {
                    val startOffset = range.displayRange.first.coerceIn(0, displayResult.displayText.length)
                    val endOffset = (range.displayRange.last + 1).coerceIn(0, displayResult.displayText.length)
                    val padding = DirectiveBoxStyle.padding

                    if (startOffset < endOffset) {
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
                                    onDirectiveTap(range.key, range.sourceText)
                                    onContentTap?.invoke()
                                }
                        )
                    } else if (range.displayText.isEmpty()) {
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
                                    onDirectiveTap(range.key, range.sourceText)
                                    onContentTap?.invoke()
                                }
                        )
                    }
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

