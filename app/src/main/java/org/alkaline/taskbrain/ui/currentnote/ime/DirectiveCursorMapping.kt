package org.alkaline.taskbrain.ui.currentnote.ime

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextLayoutResult
import org.alkaline.taskbrain.ui.currentnote.util.TappableSymbol

/**
 * Finds the tappable symbol character index at or adjacent to a tap position.
 * Handles the case where emoji renders wider than its layout slot, so the tap
 * may resolve to the next character — checks the previous char's bounding box.
 */
internal fun findTappedSymbol(
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

/**
 * Draws a blinking cursor at the given position.
 *
 * [textLayoutResultProvider] is a lambda (not a direct value) so the TextLayoutResult
 * is read during the draw phase — after layout has measured the new text. Reading it
 * during composition would give a stale layout from the previous frame, causing the
 * cursor to flash at position 0 whenever getCursorRect throws for an out-of-range index.
 */
internal fun Modifier.drawCursor(
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
