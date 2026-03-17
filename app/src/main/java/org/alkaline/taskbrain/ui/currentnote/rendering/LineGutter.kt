package org.alkaline.taskbrain.ui.currentnote.rendering

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.alkaline.taskbrain.dsl.directives.DirectiveFinder
import org.alkaline.taskbrain.dsl.directives.DirectiveResult
import org.alkaline.taskbrain.ui.currentnote.EditorConfig
import org.alkaline.taskbrain.ui.currentnote.EditorState
import org.alkaline.taskbrain.ui.currentnote.gestures.LineLayoutInfo

// Layout constants for directive edit row gaps
private val DirectiveEditRowGapHeightFallback = 40.dp  // Fallback if no measured height available
private val DefaultLineHeight = 24.dp

// =============================================================================
// Gutter Gesture Handling
// =============================================================================

/**
 * Callbacks for gutter gesture events.
 */
internal class GutterGestureCallbacks(
    val onLineSelected: (Int) -> Unit,
    val onLineDragStart: (Int) -> Unit,
    val onLineDragUpdate: (Int) -> Unit,
    val onLineDragEnd: () -> Unit
)

/**
 * Tracks state for a gutter drag gesture.
 */
private class GutterGestureTracker(
    private val startLineIndex: Int,
    private val callbacks: GutterGestureCallbacks
) {
    private var isDragging = false
    private var currentLineIndex = startLineIndex

    fun start() {
        callbacks.onLineDragStart(startLineIndex)
    }

    fun onLineChanged(newLineIndex: Int) {
        if (newLineIndex != currentLineIndex) {
            isDragging = true
            currentLineIndex = newLineIndex
            callbacks.onLineDragUpdate(currentLineIndex)
        }
    }

    fun complete() {
        if (!isDragging) {
            callbacks.onLineSelected(startLineIndex)
        }
        callbacks.onLineDragEnd()
    }
}

/**
 * Data needed to compute gutter layouts on-demand during gesture handling.
 */
private class GutterLayoutData(
    val lineCount: Int,
    val lineLayouts: List<LineLayoutInfo>,
    val state: EditorState,
    val hiddenIndices: Set<Int>,
    val directiveResults: Map<String, DirectiveResult>,
    val directiveEditHeights: Map<String, Int>,
    val defaultLineHeight: Float,
    val fallbackGapHeight: Float
) {
    fun computeLayouts(): List<GutterLineLayout> =
        computeGutterLineLayouts(lineCount, lineLayouts, state, hiddenIndices, directiveResults, directiveEditHeights, defaultLineHeight, fallbackGapHeight)
}

/**
 * Modifier that handles gutter tap and drag gestures using gutter-specific layout tracking.
 * Restarts when line count or directive heights change to ensure fresh layout data.
 * Layouts are computed on-demand during gesture handling.
 */
private fun Modifier.gutterPointerInputWithLayouts(
    layoutData: GutterLayoutData,
    directiveEditHeights: Map<String, Int>,
    callbacks: GutterGestureCallbacks
): Modifier = this.pointerInput(layoutData.lineCount, directiveEditHeights.keys.toSet(), directiveEditHeights.values.toList()) {
    awaitEachGesture {
        // Compute layouts at gesture start to get current state
        val layouts = layoutData.computeLayouts()
        val tracker = awaitGutterGestureStartWithLayouts(layouts, layoutData.lineCount, layoutData.defaultLineHeight, callbacks)
            ?: return@awaitEachGesture

        tracker.start()
        // Use the same layouts throughout the gesture for consistency
        trackGutterDragWithLayouts(tracker, layouts, layoutData.lineCount, layoutData.defaultLineHeight)
        tracker.complete()
    }
}

/**
 * Finds which line index contains the given Y position in the gutter's coordinate space.
 */
private fun findLineIndexAtYInGutter(
    y: Float,
    gutterLineLayouts: List<GutterLineLayout>,
    maxLineIndex: Int,
    defaultLineHeight: Float
): Int {
    if (maxLineIndex < 0) return 0

    // First pass: find exact match
    for (layout in gutterLineLayouts) {
        if (layout.height <= 0f) continue
        val layoutEnd = layout.yOffset + layout.height
        if (y >= layout.yOffset && y < layoutEnd) {
            return layout.lineIndex.coerceIn(0, maxLineIndex)
        }
    }

    // Second pass: find closest line above the position (line whose top is <= y)
    var closestLine = 0
    var closestOffset = -1f
    for (layout in gutterLineLayouts) {
        if (layout.height <= 0f) continue
        if (y >= layout.yOffset && layout.yOffset > closestOffset) {
            closestOffset = layout.yOffset
            closestLine = layout.lineIndex
        }
    }

    // Check if position is beyond all lines
    val lastValidLayout = gutterLineLayouts.lastOrNull { it.height > 0f }
    if (lastValidLayout != null && y >= lastValidLayout.yOffset + lastValidLayout.height) {
        return lastValidLayout.lineIndex.coerceIn(0, maxLineIndex)
    }

    // Fallback: estimate based on default height
    if (defaultLineHeight > 0f && closestOffset < 0f) {
        return (y / defaultLineHeight).toInt().coerceIn(0, maxLineIndex)
    }

    return closestLine.coerceIn(0, maxLineIndex)
}

private suspend fun AwaitPointerEventScope.awaitGutterGestureStartWithLayouts(
    gutterLineLayouts: List<GutterLineLayout>,
    lineCount: Int,
    defaultLineHeight: Float,
    callbacks: GutterGestureCallbacks
): GutterGestureTracker? {
    val down = awaitFirstDown()
    val y = down.position.y
    val maxLineIndex = (lineCount - 1).coerceAtLeast(0)
    val startLineIndex = findLineIndexAtYInGutter(y, gutterLineLayouts, maxLineIndex, defaultLineHeight)

    if (startLineIndex !in 0 until lineCount) {
        return null
    }

    return GutterGestureTracker(startLineIndex, callbacks)
}

private suspend fun AwaitPointerEventScope.trackGutterDragWithLayouts(
    tracker: GutterGestureTracker,
    gutterLineLayouts: List<GutterLineLayout>,
    lineCount: Int,
    defaultLineHeight: Float
) {
    val maxLineIndex = (lineCount - 1).coerceAtLeast(0)

    do {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull() ?: break

        if (change.pressed) {
            val y = change.position.y
            val newLineIndex = findLineIndexAtYInGutter(
                y,
                gutterLineLayouts,
                maxLineIndex,
                defaultLineHeight
            )
            tracker.onLineChanged(newLineIndex)
            change.consume()
        }
    } while (event.changes.any { it.pressed })
}

// =============================================================================
// Selection Detection
// =============================================================================

/**
 * Checks if a line is within the current selection.
 */
internal fun isLineInSelection(lineIndex: Int, state: EditorState): Boolean {
    if (!state.hasSelection) return false

    val lineStart = state.getLineStartOffset(lineIndex)
    val lineEnd = lineStart + (state.lines.getOrNull(lineIndex)?.text?.length ?: 0)

    val selMin = state.selection.min
    val selMax = state.selection.max

    // Line is selected if any part of it overlaps with the selection
    return if (lineStart == lineEnd) {
        // Empty line: selected if selection spans across this position
        lineStart >= selMin && lineStart < selMax
    } else {
        // Non-empty line: selected if any part overlaps
        lineEnd > selMin && lineStart < selMax
    }
}

// =============================================================================
// Display Composables
// =============================================================================

/**
 * Computed layout info for a gutter line box.
 */
private data class GutterLineLayout(
    val lineIndex: Int,
    val yOffset: Float,
    val height: Float
)

/**
 * Computes gutter line layouts based on line heights and expanded directive gaps.
 * This provides deterministic positions for hit testing.
 */
private fun computeGutterLineLayouts(
    lineCount: Int,
    lineLayouts: List<LineLayoutInfo>,
    state: EditorState,
    hiddenIndices: Set<Int>,
    directiveResults: Map<String, DirectiveResult>,
    directiveEditHeights: Map<String, Int>,
    defaultLineHeight: Float,
    fallbackGapHeight: Float
): List<GutterLineLayout> {
    val result = mutableListOf<GutterLineLayout>()
    var currentY = 0f
    var index = 0

    while (index < lineCount) {
        if (index in hiddenIndices) {
            // Skip contiguous hidden block — add one placeholder-height entry
            val blockStart = index
            while (index < lineCount && index in hiddenIndices) {
                index++
            }
            // Map the placeholder to the first hidden line index for gesture purposes
            result.add(GutterLineLayout(blockStart, currentY, defaultLineHeight))
            currentY += defaultLineHeight
            continue
        }

        val layoutInfo = lineLayouts.getOrNull(index)
        val lineHeight = layoutInfo?.height?.takeIf { it > 0f } ?: defaultLineHeight

        result.add(GutterLineLayout(index, currentY, lineHeight))
        currentY += lineHeight

        // Add gap height for each expanded directive on this line
        val lineContent = state.lines.getOrNull(index)?.content ?: ""
        val lineDirectives = DirectiveFinder.findDirectives(lineContent)
        for (found in lineDirectives) {
            val key = DirectiveFinder.directiveKey(index, found.startOffset)
            val directiveResult = directiveResults[key]
            if (directiveResult != null && !directiveResult.collapsed) {
                val measuredHeight = directiveEditHeights[key]
                currentY += measuredHeight?.toFloat() ?: fallbackGapHeight
            }
        }
        index++
    }

    return result
}

/**
 * A composable gutter that appears to the left of the editor.
 * Allows line selection by tapping or dragging on line boxes.
 * Each box height matches the corresponding line's height (including wrapped lines).
 */
@Composable
internal fun LineGutter(
    lineLayouts: List<LineLayoutInfo>,
    state: EditorState,
    hiddenIndices: Set<Int> = emptySet(),
    directiveResults: Map<String, DirectiveResult> = emptyMap(),
    directiveEditHeights: Map<String, Int> = emptyMap(),
    onLineSelected: (Int) -> Unit,
    onLineDragStart: (Int) -> Unit,
    onLineDragUpdate: (Int) -> Unit,
    onLineDragEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val gutterWidthPx = with(density) { EditorConfig.GutterWidth.toPx() }
    val defaultLineHeight = with(density) { DefaultLineHeight.toPx() }
    val fallbackGapHeightPx = with(density) { DirectiveEditRowGapHeightFallback.toPx() }

    // Data needed to compute layouts on-demand during gesture handling
    val layoutData = GutterLayoutData(
        lineCount = state.lines.size,
        lineLayouts = lineLayouts,
        state = state,
        hiddenIndices = hiddenIndices,
        directiveResults = directiveResults,
        directiveEditHeights = directiveEditHeights,
        defaultLineHeight = defaultLineHeight,
        fallbackGapHeight = fallbackGapHeightPx
    )

    val callbacks = GutterGestureCallbacks(
        onLineSelected = onLineSelected,
        onLineDragStart = onLineDragStart,
        onLineDragUpdate = onLineDragUpdate,
        onLineDragEnd = onLineDragEnd
    )

    Column(
        modifier = modifier
            .width(EditorConfig.GutterWidth)
            .gutterPointerInputWithLayouts(layoutData, directiveEditHeights, callbacks)
    ) {
        GutterContent(
            lineCount = state.lines.size,
            lineLayouts = lineLayouts,
            state = state,
            hiddenIndices = hiddenIndices,
            directiveResults = directiveResults,
            directiveEditHeights = directiveEditHeights,
            defaultLineHeight = defaultLineHeight,
            fallbackGapHeight = fallbackGapHeightPx,
            gutterWidthPx = gutterWidthPx,
            density = density
        )
    }
}

@Composable
private fun GutterContent(
    lineCount: Int,
    lineLayouts: List<LineLayoutInfo>,
    state: EditorState,
    hiddenIndices: Set<Int>,
    directiveResults: Map<String, DirectiveResult>,
    directiveEditHeights: Map<String, Int>,
    defaultLineHeight: Float,
    fallbackGapHeight: Float,
    gutterWidthPx: Float,
    density: androidx.compose.ui.unit.Density
) {
    var index = 0
    while (index < lineCount) {
        if (index in hiddenIndices) {
            // Skip contiguous hidden block — render one placeholder-height gutter box
            val placeholderHeight = with(density) { DefaultLineHeight.toPx() }
            val blockStart = index
            while (index < lineCount && index in hiddenIndices) {
                index++
            }
            // Block is selected if any hidden line in it overlaps the selection
            val blockSelected = (blockStart until index).any { isLineInSelection(it, state) }
            GutterBox(
                height = with(density) { placeholderHeight.toDp() },
                width = EditorConfig.GutterWidth,
                isSelected = blockSelected,
                gutterWidthPx = gutterWidthPx
            )
            continue
        }

        val layoutInfo = lineLayouts.getOrNull(index)
        val lineHeight = layoutInfo?.height?.takeIf { it > 0f } ?: defaultLineHeight
        val isSelected = isLineInSelection(index, state)

        GutterBox(
            height = with(density) { lineHeight.toDp() },
            width = EditorConfig.GutterWidth,
            isSelected = isSelected,
            gutterWidthPx = gutterWidthPx
        )

        // Add gaps for expanded directive edit rows on this line
        val lineContent = state.lines.getOrNull(index)?.content ?: ""
        val lineDirectives = DirectiveFinder.findDirectives(lineContent)
        for (found in lineDirectives) {
            val key = DirectiveFinder.directiveKey(index, found.startOffset)
            val result = directiveResults[key]
            if (result != null && !result.collapsed) {
                val measuredHeight = directiveEditHeights[key]
                val gapHeight = with(density) {
                    (measuredHeight?.toFloat() ?: fallbackGapHeight).toDp()
                }
                GutterGap(height = gapHeight, width = EditorConfig.GutterWidth)
            }
        }
        index++
    }
}

/**
 * An empty gap in the gutter for directive edit rows.
 */
@Composable
private fun GutterGap(
    height: Dp,
    width: Dp
) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
    )
}

/**
 * A single gutter box for one logical line.
 */
@Composable
private fun GutterBox(
    height: Dp,
    width: Dp,
    isSelected: Boolean,
    gutterWidthPx: Float
) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .drawBehind {
                drawRect(
                    color = if (isSelected) EditorConfig.GutterSelectionColor else EditorConfig.GutterBackgroundColor,
                    size = Size(gutterWidthPx, size.height)
                )
                drawLine(
                    color = EditorConfig.GutterLineColor,
                    start = Offset(0f, size.height),
                    end = Offset(gutterWidthPx, size.height),
                    strokeWidth = 1f
                )
            }
    )
}
