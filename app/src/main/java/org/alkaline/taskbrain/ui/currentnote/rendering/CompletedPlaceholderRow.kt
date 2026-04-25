package org.alkaline.taskbrain.ui.currentnote.rendering

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import org.alkaline.taskbrain.R

/**
 * Non-editable placeholder row shown when completed lines are hidden.
 * Displays "(N completed)" at the correct indent level using tab characters.
 *
 * Renders structurally identical to a regular line so its height matches —
 * any extra padding here would cause the gutter to drift relative to the
 * editor lines as more placeholders accumulate.
 */
@Composable
fun CompletedPlaceholderRow(
    count: Int,
    indentLevel: Int,
    textStyle: TextStyle,
    onHeightMeasured: (Float) -> Unit = {},
) {
    val tabs = "\t".repeat(indentLevel)
    BasicText(
        text = tabs + stringResource(R.string.completed_count, count),
        style = textStyle.copy(color = Color.Gray),
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                onHeightMeasured(coordinates.size.height.toFloat())
            }
    )
}
