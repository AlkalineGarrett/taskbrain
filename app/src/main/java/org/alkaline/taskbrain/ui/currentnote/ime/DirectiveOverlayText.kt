package org.alkaline.taskbrain.ui.currentnote.ime

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import org.alkaline.taskbrain.dsl.directives.DirectiveDisplayRange
import org.alkaline.taskbrain.dsl.directives.DisplayTextResult
import org.alkaline.taskbrain.dsl.directives.mapDisplayToSourceOffset
import org.alkaline.taskbrain.dsl.directives.mapSourceToDisplayOffset
import org.alkaline.taskbrain.ui.currentnote.util.SymbolOverlay
import org.alkaline.taskbrain.ui.currentnote.util.drawSymbolOverlays
import org.alkaline.taskbrain.ui.currentnote.util.hasVisibleBadges

private val DirectiveErrorColor = Color(0xFFF44336)
private val DirectiveWarningColor = Color(0xFFFF9800)
private val DirectiveSuccessColor = Color(0xFF4CAF50)

private object DirectiveBoxStyle {
    val strokeWidth = 1.dp
    val dashLength = 4.dp
    val gapLength = 2.dp
    val cornerRadius = 3.dp
    val padding = 2.dp
}

private val EmptyDirectiveTapWidth = 16.dp

/**
 * Pre-computed geometry for a single directive's overlay box. Built once per
 * (displayResult, textLayoutResult) pair so the draw pass and the tap-target
 * pass don't both call into `getPathForRange` / `getCursorRect`.
 */
private sealed class DirectiveBoxGeometry {
    abstract val range: DirectiveDisplayRange

    data class NonEmpty(
        override val range: DirectiveDisplayRange,
        val bounds: Rect,
    ) : DirectiveBoxGeometry()

    data class EmptyPlaceholder(
        override val range: DirectiveDisplayRange,
        val cursorRect: Rect,
    ) : DirectiveBoxGeometry()
}

private fun computeDirectiveBoxes(
    displayResult: DisplayTextResult,
    layout: TextLayoutResult,
): List<DirectiveBoxGeometry> {
    val textLength = displayResult.displayText.length
    return displayResult.directiveDisplayRanges.mapNotNull { range ->
        if (range.isAlarm) return@mapNotNull null
        val start = range.displayRange.first.coerceIn(0, textLength)
        val end = (range.displayRange.last + 1).coerceIn(0, textLength)
        when {
            start < end -> DirectiveBoxGeometry.NonEmpty(
                range,
                layout.getPathForRange(start, end).getBounds(),
            )
            range.displayText.isEmpty() -> {
                val cursorRect = try {
                    layout.getCursorRect(start)
                } catch (_: Exception) {
                    Rect(0f, 0f, 2f, layout.size.height.toFloat())
                }
                DirectiveBoxGeometry.EmptyPlaceholder(range, cursorRect)
            }
            else -> null
        }
    }
}

private fun DirectiveDisplayRange.boxColor(): Color = when {
    hasError -> DirectiveErrorColor
    hasWarning -> DirectiveWarningColor
    else -> DirectiveSuccessColor
}

/**
 * Renders display text with directive boxes as overlays.
 * Uses a single BasicText for correct cursor positioning.
 */
@Composable
internal fun DirectiveOverlayText(
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
    onTapAtSourcePosition: (sourcePosition: Int, tapOffset: Offset) -> Unit,
    onSymbolTap: ((lineIndex: Int, charOffsetInLine: Int) -> Unit)? = null,
    symbolOverlays: List<SymbolOverlay> = emptyList()
) {
    var textLayoutResult: TextLayoutResult? by remember { mutableStateOf(null) }
    val hasOverlays = symbolOverlays.hasVisibleBadges()
    val overlayTextMeasurer = if (hasOverlays) rememberTextMeasurer() else null

    // Layout-keyed snapshot consumed by both the draw pass and the tap-target
    // composables. Recomputes only when the layout (or directive set) changes.
    val validLayout = textLayoutResult?.takeIf {
        it.layoutInput.text.length == displayResult.displayText.length
    }
    val directiveBoxes = remember(displayResult, validLayout) {
        if (validLayout == null) emptyList() else computeDirectiveBoxes(displayResult, validLayout)
    }

    Box(modifier = Modifier.fillMaxWidth()) {
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
                        val layout = textLayoutResult ?: return@detectTapGestures
                        val displayPosition = layout.getOffsetForPosition(offset)
                        val sourcePosition = mapDisplayToSourceOffset(displayPosition, displayResult)

                        // Use display position to find a candidate alarm range, then verify
                        // against the actual bounding box. getOffsetForPosition clamps to
                        // text length, so taps in empty space to the right of the alarm
                        // emoji would otherwise resolve to it.
                        val alarmDirective = displayResult.directiveDisplayRanges.find {
                            it.isAlarm && displayPosition >= it.displayRange.first
                                && displayPosition <= it.displayRange.last + 1
                        }
                        if (alarmDirective != null && onSymbolTap != null) {
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
                                val dispIdx = mapSourceToDisplayOffset(sourceIdx, displayResult)
                                if (dispIdx < layout.layoutInput.text.length) layout.getBoundingBox(dispIdx)
                                else Rect.Zero
                            },
                            tapX = offset.x
                        )
                        if (symbolIndex != null && onSymbolTap != null) {
                            onSymbolTap(lineIndex, symbolIndex)
                        } else {
                            onTapAtSourcePosition(sourcePosition, offset)
                        }
                    }
                }
                .drawWithContent {
                    drawContent()
                    val layout = validLayout ?: return@drawWithContent

                    if (isFocused && !hasExternalSelection && !cursorInDirective) {
                        val cursorPos = displayCursor.coerceIn(0, displayResult.displayText.length)
                        val cursorRect = try {
                            layout.getCursorRect(cursorPos)
                        } catch (_: Exception) {
                            Rect(0f, 0f, CursorWidth.toPx(), layout.size.height.toFloat())
                        }
                        drawLine(
                            color = CursorColor.copy(alpha = cursorAlpha),
                            start = Offset(cursorRect.left, cursorRect.top),
                            end = Offset(cursorRect.left, cursorRect.bottom),
                            strokeWidth = CursorWidth.toPx()
                        )
                    }

                    val padding = DirectiveBoxStyle.padding.toPx()
                    val strokeWidth = DirectiveBoxStyle.strokeWidth.toPx()
                    val cornerRadius = DirectiveBoxStyle.cornerRadius.toPx()
                    val dashLength = DirectiveBoxStyle.dashLength.toPx()
                    val gapLength = DirectiveBoxStyle.gapLength.toPx()
                    for (box in directiveBoxes) {
                        val boxColor = box.range.boxColor()
                        when (box) {
                            is DirectiveBoxGeometry.NonEmpty -> {
                                val b = box.bounds
                                drawRoundRect(
                                    color = boxColor,
                                    topLeft = Offset(b.left - padding, b.top - padding),
                                    size = Size(b.width + padding * 2, b.height + padding * 2),
                                    cornerRadius = CornerRadius(cornerRadius),
                                    style = Stroke(
                                        width = strokeWidth,
                                        pathEffect = PathEffect.dashPathEffect(
                                            floatArrayOf(dashLength, gapLength)
                                        )
                                    )
                                )
                            }
                            is DirectiveBoxGeometry.EmptyPlaceholder -> {
                                val r = box.cursorRect
                                drawLine(
                                    color = boxColor,
                                    start = Offset(r.left, r.top + padding),
                                    end = Offset(r.left, r.bottom - padding),
                                    strokeWidth = strokeWidth * 1.5f,
                                    pathEffect = PathEffect.dashPathEffect(
                                        floatArrayOf(dashLength * 0.75f, gapLength)
                                    )
                                )
                            }
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

        // Invisible tap targets — alarm ranges are handled by the BasicText pointerInput above.
        Box(modifier = Modifier.matchParentSize()) {
            if (directiveBoxes.isEmpty()) return@Box
            val density = LocalDensity.current
            val padding = DirectiveBoxStyle.padding
            for (box in directiveBoxes) {
                when (box) {
                    is DirectiveBoxGeometry.NonEmpty -> {
                        val b = box.bounds
                        Box(
                            modifier = Modifier
                                .offset(
                                    x = with(density) { (b.left - padding.toPx()).toDp() },
                                    y = with(density) { (b.top - padding.toPx()).toDp() }
                                )
                                .size(
                                    width = with(density) { (b.width + padding.toPx() * 2).toDp() },
                                    height = with(density) { (b.height + padding.toPx() * 2).toDp() }
                                )
                                .clickable {
                                    onDirectiveTap?.invoke(box.range.key, box.range.sourceText)
                                }
                        )
                    }
                    is DirectiveBoxGeometry.EmptyPlaceholder -> {
                        val r = box.cursorRect
                        Box(
                            modifier = Modifier
                                .offset(
                                    x = with(density) { (r.left - EmptyDirectiveTapWidth.toPx() / 2).toDp() },
                                    y = with(density) { r.top.toDp() }
                                )
                                .size(
                                    width = EmptyDirectiveTapWidth,
                                    height = with(density) { (r.bottom - r.top).toDp() }
                                )
                                .clickable {
                                    onDirectiveTap?.invoke(box.range.key, box.range.sourceText)
                                }
                        )
                    }
                }
            }
        }
    }
}
