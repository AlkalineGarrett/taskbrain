package org.alkaline.taskbrain.ui.currentnote.ime

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.alkaline.taskbrain.dsl.runtime.values.ButtonVal
import org.alkaline.taskbrain.dsl.ui.ButtonExecutionState

private val ButtonMinHeight = 32.dp
private val ButtonCornerRadius = 4.dp
private val ButtonIconSize = 16.dp
private val ButtonBackground = Color(0xFF2196F3)
private val ButtonLoadingBackground = Color(0xFF90CAF9)
private val ButtonSuccessBackground = Color(0xFF4CAF50)
private val ButtonErrorBackground = Color(0xFFF44336)
private val ButtonContentColor = Color.White

/**
 * Button directive rendered as an interactive button with settings icon.
 * Shows the button label and executes the action when clicked.
 * Displays error message below the button if execution failed.
 */
@Composable
internal fun ButtonDirectiveInlineContent(
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
        Row(verticalAlignment = Alignment.CenterVertically) {
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
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
