package org.alkaline.taskbrain.ui.currentnote.rendering

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import org.alkaline.taskbrain.R

/**
 * Non-editable placeholder row shown when completed lines are hidden.
 * Displays "(N completed)" at the correct indent level using tab characters.
 */
@Composable
fun CompletedPlaceholderRow(
    count: Int,
    indentLevel: Int,
    textStyle: TextStyle,
) {
    val tabs = "\t".repeat(indentLevel)
    Text(
        text = tabs + stringResource(R.string.completed_count, count),
        style = textStyle.copy(color = Color.Gray),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    )
}
