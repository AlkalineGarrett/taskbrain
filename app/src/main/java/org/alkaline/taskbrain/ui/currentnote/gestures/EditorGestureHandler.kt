package org.alkaline.taskbrain.ui.currentnote.gestures

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.alkaline.taskbrain.dsl.directives.DirectiveResult
import org.alkaline.taskbrain.dsl.directives.DirectiveSegmenter
import org.alkaline.taskbrain.dsl.directives.DisplayTextResult
import org.alkaline.taskbrain.dsl.directives.mapDisplayToSourceOffset
import org.alkaline.taskbrain.dsl.directives.mapSourceToDisplayOffset
import org.alkaline.taskbrain.ui.currentnote.EditorConfig
import org.alkaline.taskbrain.ui.currentnote.EditorState

/**
 * Tracks layout information for a line, used for hit testing during selection.
 */
internal data class LineLayoutInfo(
    val lineIndex: Int,
    val yOffset: Float,
    val height: Float,
    val textLayoutResult: TextLayoutResult?,
    val prefixWidthPx: Float = 0f
)

// =============================================================================
// Position Conversion Utilities
// =============================================================================

/**
 * Finds which line index contains the given Y position.
 * Returns the line index, or 0 if no lines exist.
 *
 * @param y The Y position to find the line for
 * @param lineLayouts Layout information for each line
 * @param maxLineIndex Maximum valid line index (used for clamping)
 * @param defaultLineHeight Optional fallback line height for estimation when layouts are invalid
 */
internal fun findLineIndexAtY(
    y: Float,
    lineLayouts: List<LineLayoutInfo>,
    maxLineIndex: Int,
    defaultLineHeight: Float = 0f
): Int {
    if (maxLineIndex < 0) return 0

    // First pass: find exact match
    for (i in lineLayouts.indices) {
        val layout = lineLayouts[i]
        if (layout.height <= 0f) continue

        if (y >= layout.yOffset && y < layout.yOffset + layout.height) {
            return i.coerceIn(0, maxLineIndex)
        }
    }

    // Second pass: find closest line above the position
    var closestLine = 0
    for (i in lineLayouts.indices) {
        val layout = lineLayouts[i]
        if (layout.height <= 0f) continue
        if (y >= layout.yOffset) {
            closestLine = i
        }
    }

    // Check if position is beyond all lines
    val lastValidLayout = lineLayouts.lastOrNull { it.height > 0f }
    if (lastValidLayout != null && y >= lastValidLayout.yOffset + lastValidLayout.height) {
        return lastValidLayout.lineIndex.coerceIn(0, maxLineIndex)
    }

    // Fallback: estimate based on average line height or provided default
    val validLayouts = lineLayouts.filter { it.height > 0f }
    val fallbackHeight = if (validLayouts.isNotEmpty()) {
        validLayouts.map { it.height }.average().toFloat()
    } else {
        defaultLineHeight
    }

    if (fallbackHeight > 0f) {
        val estimated = (y / fallbackHeight).toInt().coerceIn(0, maxLineIndex)
        return estimated
    }

    return closestLine.coerceIn(0, maxLineIndex)
}

/**
 * Converts a screen position to a global character offset in the editor text.
 *
 * When directives are present, the displayed text may differ from the source text
 * (e.g., "[date]" displays as "Jan 25, 2026"). This function handles the mapping
 * from display coordinates to source coordinates.
 */
internal fun positionToGlobalOffset(
    position: Offset,
    state: EditorState,
    lineLayouts: List<LineLayoutInfo>,
    directiveResults: Map<String, DirectiveResult> = emptyMap()
): Int {
    if (state.lines.isEmpty()) return 0

    val lineIndex = findLineIndexAtY(position.y, lineLayouts, state.lines.lastIndex)
    val lineState = state.lines.getOrNull(lineIndex) ?: return 0
    val layoutInfo = lineLayouts.getOrNull(lineIndex)

    // Build display text info to map between display and source positions
    val displayResult = DirectiveSegmenter.buildDisplayText(
        lineState.content, directiveResults, lineState.noteIds.firstOrNull()
    )

    val displayOffset = getCharacterOffsetInContent(
        position = position,
        lineYOffset = layoutInfo?.yOffset ?: 0f,
        prefixWidthPx = layoutInfo?.prefixWidthPx ?: (lineState.prefix.length * EditorConfig.EstimatedCharWidthPx),
        contentLength = displayResult.displayText.length,
        textLayout = layoutInfo?.textLayoutResult
    )

    // Map display offset to source offset
    val sourceOffset = mapDisplayToSourceOffset(displayOffset, displayResult)

    return state.getLineStartOffset(lineIndex) + lineState.prefix.length + sourceOffset
}

/**
 * Calculates the character offset within a line's content based on screen X position.
 */
private fun getCharacterOffsetInContent(
    position: Offset,
    lineYOffset: Float,
    prefixWidthPx: Float,
    contentLength: Int,
    textLayout: TextLayoutResult?
): Int {
    val localX = (position.x - prefixWidthPx).coerceAtLeast(0f)
    val localY = (position.y - lineYOffset).coerceAtLeast(0f)

    if (textLayout != null) {
        return try {
            textLayout.getOffsetForPosition(Offset(localX, localY))
        } catch (e: Exception) {
            estimateCharacterOffset(localX, textLayout, contentLength)
        }
    }

    return (localX / EditorConfig.EstimatedCharWidthPx).toInt().coerceIn(0, contentLength)
}

private fun estimateCharacterOffset(
    localX: Float,
    textLayout: TextLayoutResult,
    contentLength: Int
): Int {
    val avgCharWidth = if (textLayout.size.width > 0 && contentLength > 0) {
        textLayout.size.width.toFloat() / contentLength
    } else {
        EditorConfig.EstimatedCharWidthPx
    }
    return (localX / avgCharWidth).toInt().coerceIn(0, contentLength)
}

// =============================================================================
// Word Boundary Detection
// =============================================================================

/**
 * Finds word boundaries at a given character offset in the text.
 * Returns a pair of (wordStart, wordEnd).
 *
 * Word characters are letters and digits. Punctuation is excluded from word selection
 * to match standard text editor behavior.
 *
 * For empty lines or positions with only whitespace/punctuation, includes at least
 * the newline character if available.
 */
internal fun findWordBoundaries(text: String, offset: Int): Pair<Int, Int> {
    if (text.isEmpty()) {
        return 0 to 0
    }

    // Clamp offset to valid range
    val clampedOffset = offset.coerceIn(0, text.length)

    // Check if we're on a word character (letter or digit)
    val isOnWordChar = clampedOffset < text.length && text[clampedOffset].isLetterOrDigit()
    val isPrevWordChar = clampedOffset > 0 && text[clampedOffset - 1].isLetterOrDigit()

    // Find word boundaries using letters and digits only
    var wordStart = clampedOffset
    var wordEnd = clampedOffset

    if (isOnWordChar || isPrevWordChar) {
        // Expand backward to find word start
        wordStart = clampedOffset
        while (wordStart > 0 && text[wordStart - 1].isLetterOrDigit()) {
            wordStart--
        }

        // Expand forward to find word end
        wordEnd = clampedOffset
        while (wordEnd < text.length && text[wordEnd].isLetterOrDigit()) {
            wordEnd++
        }
    }

    // If no word found, try to select at least a newline character
    if (wordStart == wordEnd) {
        if (clampedOffset < text.length && text[clampedOffset] == '\n') {
            wordEnd = clampedOffset + 1
        } else if (clampedOffset > 0 && text[clampedOffset - 1] == '\n') {
            if (clampedOffset < text.length && text[clampedOffset] == '\n') {
                wordEnd = clampedOffset + 1
            }
        }
    }

    return wordStart to wordEnd
}

// =============================================================================
// Cursor Proximity Detection
// =============================================================================


/**
 * Computes the cursor's screen position from layout info.
 * Returns null if the cursor's line layout isn't available.
 */
private fun getCursorScreenPosition(
    state: EditorState,
    lineLayouts: List<LineLayoutInfo>,
    directiveResults: Map<String, DirectiveResult>
): Offset? {
    val lineIndex = state.focusedLineIndex
    val lineState = state.lines.getOrNull(lineIndex) ?: return null
    val layoutInfo = lineLayouts.getOrNull(lineIndex) ?: return null
    val textLayout = layoutInfo.textLayoutResult ?: return null

    // Map source cursor position to display position for directive-aware lines
    val contentCursor = lineState.contentCursorPosition
    val displayResult = DirectiveSegmenter.buildDisplayText(
        lineState.content, directiveResults, lineState.noteIds.firstOrNull()
    )
    val displayCursor = mapSourceToDisplayOffset(contentCursor, displayResult, mapInsideDirective = false)
        .coerceIn(0, displayResult.displayText.length)

    val cursorRect = try {
        textLayout.getCursorRect(displayCursor)
    } catch (_: Exception) {
        return null
    }

    return Offset(
        x = cursorRect.left + layoutInfo.prefixWidthPx,
        y = layoutInfo.yOffset + (cursorRect.top + cursorRect.bottom) / 2f
    )
}


// =============================================================================
// Gesture Tracking
// =============================================================================

/**
 * Pure state machine for gesture tracking, decoupled from Compose layout internals.
 *
 * Supports three gesture modes:
 * - **Tap**: positions cursor on release
 * - **Cursor drag**: if touch starts near cursor and moves before long press,
 *   cursor follows the finger (like standard Android text editors)
 * - **Selection**: long press selects a word, then drag extends selection
 *
 * @param downNearCursor whether the down position was within the cursor drag radius
 * @param touchSlop pixel distance threshold to distinguish taps from drags
 * @param resolveOffset converts a screen position to a global character offset
 * @param resolveBoundaries finds word boundaries at a global offset
 * @param setSelection sets a selection range on the editor state
 * @param hasSelection returns whether a selection is active
 * @param isOffsetInSelection returns whether an offset falls inside the current selection
 * @param onCursorDragged called with a global offset when cursor drag moves
 */
internal class GestureStateMachine(
    private val downPosition: Offset,
    private val downNearCursor: Boolean,
    private val touchSlop: Float,
    private val resolveOffset: (Offset) -> Int,
    private val resolveBoundaries: (Int) -> Pair<Int, Int>,
    private val setSelection: (Int, Int) -> Unit,
    private val hasSelection: () -> Boolean,
    private val isOffsetInSelection: (Int) -> Boolean,
    private val onCursorDragged: (Int) -> Unit
) {
    var longPressTriggered = false
        private set
    var isScrollGesture = false
        private set
    var isCursorDragGesture = false
        private set
    var consumedByChild = false
        private set
    var lastPosition = downPosition
        private set

    private var anchorStart = -1
    private var anchorEnd = -1
    private var totalDragDistance = 0f

    fun markConsumedByChild() {
        consumedByChild = true
    }

    /**
     * Called when long press timer fires.
     * Selects the word at the down position — but only if not already in cursor drag mode.
     */
    fun onLongPressTriggered() {
        if (isScrollGesture || isCursorDragGesture) return

        longPressTriggered = true
        val globalOffset = resolveOffset(downPosition)
        val (wordStart, wordEnd) = resolveBoundaries(globalOffset)
        anchorStart = wordStart
        anchorEnd = wordEnd
        setSelection(wordStart, wordEnd)
    }

    /**
     * Processes a pointer move event.
     * Returns true if the event should be consumed (prevent scrolling).
     */
    fun onPointerMove(position: Offset): Boolean {
        val dragDelta = position - lastPosition
        totalDragDistance += dragDelta.getDistance()
        lastPosition = position

        // Already in cursor drag mode — move cursor to follow finger
        if (isCursorDragGesture) {
            onCursorDragged(resolveOffset(position))
            return true
        }

        // In selection mode — update selection
        if (longPressTriggered && anchorStart >= 0) {
            updateDragSelection(position)
            return true
        }

        // Check if drag exceeds touch slop (before long press)
        if (!longPressTriggered && !isScrollGesture && totalDragDistance > touchSlop) {
            if (downNearCursor) {
                isCursorDragGesture = true
                onCursorDragged(resolveOffset(position))
                return true
            } else {
                isScrollGesture = true
                return false
            }
        }

        return false
    }

    private fun updateDragSelection(position: Offset) {
        val globalOffset = resolveOffset(position)
        val selStart = minOf(anchorStart, globalOffset)
        val selEnd = maxOf(anchorEnd, globalOffset)
        setSelection(selStart, selEnd)
    }

    /**
     * Handles gesture completion when pointer is released.
     */
    fun onGestureComplete(
        onCursorPositioned: (Int) -> Unit,
        onTapOnSelection: ((Offset) -> Unit)?,
        onSelectionCompleted: ((Offset) -> Unit)?
    ) {
        if (isScrollGesture || consumedByChild) return

        if (longPressTriggered) {
            if (hasSelection()) {
                onSelectionCompleted?.invoke(lastPosition)
            }
        } else if (isCursorDragGesture) {
            // Cursor drag completed — cursor already positioned, nothing else to do
        } else {
            handleTap(onCursorPositioned, onTapOnSelection)
        }
    }

    private fun handleTap(
        onCursorPositioned: (Int) -> Unit,
        onTapOnSelection: ((Offset) -> Unit)?
    ) {
        val globalOffset = resolveOffset(downPosition)
        if (hasSelection() && isOffsetInSelection(globalOffset)) {
            onTapOnSelection?.invoke(downPosition)
        } else {
            onCursorPositioned(globalOffset)
        }
    }
}

/**
 * Creates a [GestureStateMachine] wired to the editor's layout and state.
 */
private fun createGestureTracker(
    downPosition: Offset,
    state: EditorState,
    lineLayouts: List<LineLayoutInfo>,
    touchSlop: Float,
    cursorDragRadiusPx: Float,
    directiveResults: Map<String, DirectiveResult>,
    onCursorDragged: (Int) -> Unit
): GestureStateMachine {
    val cursorPos = getCursorScreenPosition(state, lineLayouts, directiveResults)
    val downNearCursor = cursorPos != null &&
        (downPosition - cursorPos).getDistance() <= cursorDragRadiusPx

    return GestureStateMachine(
        downPosition = downPosition,
        downNearCursor = downNearCursor,
        touchSlop = touchSlop,
        resolveOffset = { positionToGlobalOffset(it, state, lineLayouts, directiveResults) },
        resolveBoundaries = { findWordBoundaries(state.text, it) },
        setSelection = { start, end -> state.setSelection(start, end) },
        hasSelection = { state.hasSelection },
        isOffsetInSelection = { it in state.selection.min..state.selection.max },
        onCursorDragged = onCursorDragged
    )
}

// =============================================================================
// Modifier Extension
// =============================================================================

/**
 * Creates a Modifier that handles all pointer input for the editor.
 * Intercepts touch events to implement tap-to-position-cursor and long-press-to-select.
 *
 * Gesture handling strategy:
 * - Tap: Position cursor (or show context menu if tapping on active selection)
 * - Long press: Select word, then drag to extend
 * - Scroll (quick drag): Don't consume, let native scroll handle it smoothly
 */
internal fun Modifier.editorPointerInput(
    state: EditorState,
    lineLayouts: List<LineLayoutInfo>,
    longPressTimeoutMillis: Long,
    touchSlop: Float,
    density: Float,
    scrollState: ScrollState?,
    directiveResults: Map<String, DirectiveResult> = emptyMap(),
    /** Lines with inline editors — gestures on these lines are handled by the child editor. */
    inlineEditLineIndices: Set<Int> = emptySet(),
    onCursorPositioned: (Int) -> Unit,
    onTapOnSelection: ((Offset) -> Unit)? = null,
    onSelectionCompleted: ((Offset) -> Unit)? = null
): Modifier = this.pointerInput(scrollState, directiveResults) {
    val cursorDragRadiusPx = EditorConfig.CursorDragRadiusDp * density
    coroutineScope {
        awaitPointerEventScope {
            while (true) {
                val gsm = awaitGestureStart(
                    state, lineLayouts, touchSlop, cursorDragRadiusPx,
                    directiveResults, inlineEditLineIndices, onCursorPositioned
                ) ?: continue

                val longPressJob = launchLongPressDetection(longPressTimeoutMillis, gsm)

                trackPointerUntilRelease(gsm, longPressJob)

                gsm.onGestureComplete(onCursorPositioned, onTapOnSelection, onSelectionCompleted)
            }
        }
    }
}

/**
 * Waits for a pointer down event and creates a gesture tracker.
 * Uses Main pass to allow children to consume events first.
 */
private suspend fun AwaitPointerEventScope.awaitGestureStart(
    state: EditorState,
    lineLayouts: List<LineLayoutInfo>,
    touchSlop: Float,
    cursorDragRadiusPx: Float,
    directiveResults: Map<String, DirectiveResult>,
    inlineEditLineIndices: Set<Int>,
    onCursorDragged: (Int) -> Unit
): GestureStateMachine? {
    // Use Main pass so children (like DirectiveEditRow) can consume in Initial pass first
    val down = awaitPointerEvent(PointerEventPass.Main)
    val downChange = down.changes.firstOrNull { it.pressed } ?: return null
    if (downChange.isConsumed) return null

    // Skip if the touch landed on a line with an inline editor — that editor
    // has its own editorPointerInput and will handle gestures independently.
    if (inlineEditLineIndices.isNotEmpty()) {
        val touchLine = findLineIndexAtY(downChange.position.y, lineLayouts, state.lines.lastIndex)
        if (touchLine in inlineEditLineIndices) return null
    }

    return createGestureTracker(
        downChange.position, state, lineLayouts, touchSlop, cursorDragRadiusPx,
        directiveResults, onCursorDragged
    )
}

/**
 * Launches the long press detection coroutine.
 */
private fun kotlinx.coroutines.CoroutineScope.launchLongPressDetection(
    timeoutMillis: Long,
    gsm: GestureStateMachine
): Job = launch {
    delay(timeoutMillis)
    gsm.onLongPressTriggered()
}

/**
 * Tracks pointer movement until release.
 */
private suspend fun AwaitPointerEventScope.trackPointerUntilRelease(
    gsm: GestureStateMachine,
    longPressJob: Job
) {
    while (true) {
        // Use Main pass to allow children to consume first
        val event = awaitPointerEvent(PointerEventPass.Main)
        val change = event.changes.firstOrNull() ?: break

        // Skip if consumed by a child
        if (change.isConsumed) {
            longPressJob.cancel()
            gsm.markConsumedByChild()
            break
        }

        if (!change.pressed) {
            longPressJob.cancel()
            break
        }

        val shouldConsume = gsm.onPointerMove(change.position)
        if (shouldConsume) {
            change.consume()
        }

        // Cancel long press if scrolling or cursor dragging started
        if (gsm.isScrollGesture || gsm.isCursorDragGesture) {
            longPressJob.cancel()
        }
    }
}
