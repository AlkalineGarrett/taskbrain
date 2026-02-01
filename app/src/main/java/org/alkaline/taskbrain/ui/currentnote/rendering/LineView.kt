package org.alkaline.taskbrain.ui.currentnote.rendering

import androidx.compose.foundation.clickable
import org.alkaline.taskbrain.ui.currentnote.selection.ContentSelectionOverlay
import org.alkaline.taskbrain.ui.currentnote.selection.PrefixSelectionOverlay
import org.alkaline.taskbrain.ui.currentnote.selection.lineSelectionToContentSelection
import org.alkaline.taskbrain.ui.currentnote.ime.DirectiveAwareLineInput
import org.alkaline.taskbrain.dsl.directives.DirectiveResult
import org.alkaline.taskbrain.dsl.directives.DirectiveSegmenter
import org.alkaline.taskbrain.dsl.runtime.values.ButtonVal
import org.alkaline.taskbrain.dsl.ui.ButtonExecutionState
import org.alkaline.taskbrain.dsl.directives.DisplayTextResult
import org.alkaline.taskbrain.ui.currentnote.selection.lineSelectionToPrefixSelection
import org.alkaline.taskbrain.ui.currentnote.EditorController
import org.alkaline.taskbrain.ui.currentnote.LineState
import org.alkaline.taskbrain.ui.currentnote.util.LinePrefixes
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Measures the rendered width of a prefix string.
 * Returns both the Dp value for layout and the pixel value for coordinate calculations.
 */
@Composable
internal fun measurePrefixWidth(prefix: String, textStyle: TextStyle): Pair<Dp, Float> {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    if (prefix.isEmpty()) {
        return 0.dp to 0f
    }

    val widthPx = remember(prefix, textStyle) {
        val result = textMeasurer.measure(prefix, textStyle)
        result.size.width.toFloat()
    }

    return with(density) { widthPx.toDp() } to widthPx
}

// =============================================================================
// Controller-based LineView (New Architecture)
// =============================================================================

/**
 * Renders a single line using EditorController for all state management.
 *
 * This is the NEW version that uses centralized state management:
 * - All text modifications go through EditorController
 * - No callback chains for text changes
 * - Single source of truth
 */
@Composable
internal fun ControlledLineView(
    lineIndex: Int,
    lineState: LineState,
    controller: EditorController,
    textStyle: TextStyle,
    focusRequester: FocusRequester,
    selectionRange: IntRange?,
    selectionIncludesNewline: Boolean = false,
    onFocusChanged: (Boolean) -> Unit,
    onTextLayoutResult: (TextLayoutResult) -> Unit,
    onPrefixWidthMeasured: (Float) -> Unit = {},
    directiveResults: Map<String, DirectiveResult> = emptyMap(),
    onDirectiveTap: ((directiveKey: String, sourceText: String) -> Unit)? = null,
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
    val prefix = lineState.prefix
    val content = lineState.content
    val (prefixWidth, prefixWidthPx) = measurePrefixWidth(prefix, textStyle)
    val contentCursorPosition = lineState.contentCursorPosition
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    // Report prefix width in pixels for handle positioning
    LaunchedEffect(prefixWidthPx) {
        onPrefixWidthMeasured(prefixWidthPx)
    }

    // Measure space width for newline visualization
    val spaceWidth = remember(textStyle) {
        textMeasurer.measure(" ", textStyle).size.width.toFloat()
    }

    // Build display text info to map between source and display coordinates
    val displayResult = remember(content, lineIndex, directiveResults) {
        DirectiveSegmenter.buildDisplayText(content, lineIndex, directiveResults)
    }

    // Convert line selection to content selection range (in source coordinates)
    val sourceContentSelectionRange = selectionRange?.let {
        lineSelectionToContentSelection(it, prefix.length, content.length)
    }

    // Map source selection to display selection for rendering
    val displayContentSelectionRange = sourceContentSelectionRange?.let { sourceRange ->
        mapSourceRangeToDisplayRange(sourceRange, displayResult)
    }

    val prefixSelectionRange = selectionRange?.let {
        lineSelectionToPrefixSelection(it, prefix.length)
    }

    // Hide cursor whenever there's ANY selection in the editor (not just on this line)
    val hasExternalSelection = controller.hasSelection()

    // Track content TextLayoutResult for drawing selection
    var contentTextLayout by remember { mutableStateOf<TextLayoutResult?>(null) }

    // Track focus state
    var isFocused by remember { mutableStateOf(false) }

    // Check if prefix contains a checkbox (for tap handling)
    val hasCheckbox = remember(prefix) {
        LinePrefixes.hasCheckbox(prefix)
    }
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        // Prefix area - tappable if contains checkbox
        if (prefix.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .width(prefixWidth)
                    .then(
                        if (hasCheckbox) {
                            Modifier.clickable(
                                interactionSource = interactionSource,
                                indication = null // No ripple effect
                            ) {
                                controller.toggleCheckboxOnLine(lineIndex)
                            }
                        } else {
                            Modifier
                        }
                    )
            ) {
                if (prefixSelectionRange != null) {
                    PrefixSelectionOverlay(
                        prefix = prefix,
                        selectionRange = prefixSelectionRange,
                        textStyle = textStyle
                    )
                }
                BasicText(
                    text = prefix,
                    style = textStyle
                )
            }
        }

        // Content area - using LineTextInput with EditorController
        Box(modifier = Modifier.weight(1f)) {
            // Selection overlay behind text (including newline visualization)
            // Use display coordinates since contentTextLayout is for display text
            if ((displayContentSelectionRange != null || selectionIncludesNewline) && contentTextLayout != null) {
                ContentSelectionOverlay(
                    selectionRange = displayContentSelectionRange,
                    contentLength = displayResult.displayText.length,
                    textLayout = contentTextLayout!!,
                    density = density,
                    includesNewline = selectionIncludesNewline,
                    spaceWidth = spaceWidth
                )
            }

            DirectiveAwareLineInput(
                lineIndex = lineIndex,
                content = content,
                contentCursor = contentCursorPosition,
                controller = controller,
                isFocused = isFocused,
                hasExternalSelection = hasExternalSelection,
                textStyle = textStyle,
                focusRequester = focusRequester,
                directiveResults = directiveResults,
                onFocusChanged = { focused ->
                    isFocused = focused
                    onFocusChanged(focused)
                },
                onTextLayoutResult = { layout ->
                    contentTextLayout = layout
                    onTextLayoutResult(layout)
                },
                onDirectiveTap = { key, sourceText ->
                    onDirectiveTap?.invoke(key, sourceText)
                },
                onViewNoteTap = { key, noteId, noteContent ->
                    onViewNoteTap?.invoke(key, noteId, noteContent)
                },
                onViewEditDirective = { key, sourceText ->
                    onViewEditDirective?.invoke(key, sourceText)
                },
                onViewDirectiveRefresh = onViewDirectiveRefresh,
                onViewDirectiveConfirm = onViewDirectiveConfirm,
                onViewDirectiveCancel = onViewDirectiveCancel,
                onButtonClick = onButtonClick,
                buttonExecutionStates = buttonExecutionStates,
                buttonErrors = buttonErrors,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Maps a source text range to display text range.
 * Handles directives that may have different lengths in source vs display.
 */
private fun mapSourceRangeToDisplayRange(
    sourceRange: IntRange,
    displayResult: DisplayTextResult
): IntRange {
    if (displayResult.directiveDisplayRanges.isEmpty()) {
        return sourceRange
    }

    val displayStart = mapSourceToDisplayOffset(sourceRange.first, displayResult)
    // For the end, we use last + 1 because ranges are [start, end) exclusive
    val displayEnd = mapSourceToDisplayOffset(sourceRange.last + 1, displayResult)

    return displayStart until displayEnd
}

/**
 * Maps a cursor position from source text to display text.
 */
private fun mapSourceToDisplayOffset(sourceOffset: Int, displayResult: DisplayTextResult): Int {
    if (displayResult.directiveDisplayRanges.isEmpty()) {
        return sourceOffset
    }

    var displayOffset = sourceOffset

    for (range in displayResult.directiveDisplayRanges) {
        if (sourceOffset <= range.sourceRange.first) {
            // Offset is before this directive - no adjustment needed
            break
        } else if (sourceOffset > range.sourceRange.last) {
            // Offset is after this directive - adjust for the length difference
            val sourceLength = range.sourceRange.last - range.sourceRange.first + 1
            val displayLength = range.displayRange.last - range.displayRange.first + 1
            displayOffset += displayLength - sourceLength
        } else {
            // Offset is inside the directive - map to corresponding position in display
            // Map proportionally or to the end of display directive
            return range.displayRange.last + 1
        }
    }

    return displayOffset.coerceAtLeast(0)
}

