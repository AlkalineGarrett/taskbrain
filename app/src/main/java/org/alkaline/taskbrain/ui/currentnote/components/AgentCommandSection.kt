package org.alkaline.taskbrain.ui.currentnote.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.alkaline.taskbrain.R

/**
 * Expandable section for entering agent commands.
 * Includes header, processing indicator, and command text field.
 */
@Composable
fun AgentCommandSection(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    agentCommand: String,
    onAgentCommandChange: (String) -> Unit,
    isProcessing: Boolean,
    onSendCommand: () -> Unit,
    mainContentFocusRequester: FocusRequester,
    enabled: Boolean
) {
    var hasBeenFocused by remember { mutableStateOf(false) }

    // Reset focus tracking when section collapses
    LaunchedEffect(isExpanded) {
        if (!isExpanded) {
            hasBeenFocused = false
        }
    }

    AgentSectionHeader(
        isExpanded = isExpanded,
        enabled = enabled,
        onExpand = { if (enabled) onExpandedChange(true) },
        onCollapse = {
            // Safely request focus - the target component might not be in the composition tree
            try {
                mainContentFocusRequester.requestFocus()
            } catch (e: IllegalStateException) {
                // FocusRequester not attached to a focusable component, ignore
            }
            onExpandedChange(false)
        }
    )

    if (isProcessing) {
        ProcessingIndicatorBar()
    }

    if (isExpanded && enabled) {
        AgentCommandTextField(
            command = agentCommand,
            onCommandChange = onAgentCommandChange,
            isProcessing = isProcessing,
            onSendCommand = onSendCommand,
            onFocusGained = { hasBeenFocused = true },
            onFocusLost = {
                if (hasBeenFocused && agentCommand.isBlank()) {
                    onExpandedChange(false)
                }
            }
        )
    }
}

/**
 * Header bar for the agent command section.
 * Shows title and collapse button when expanded.
 */
@Composable
private fun AgentSectionHeader(
    isExpanded: Boolean,
    enabled: Boolean,
    onExpand: () -> Unit,
    onCollapse: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colorResource(R.color.titlebar_background))
            .clickable { onExpand() }
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (enabled) stringResource(id = R.string.agent_chat_label)
                   else stringResource(id = R.string.ai_unavailable_offline),
            color = if (enabled) colorResource(R.color.titlebar_text)
                    else colorResource(R.color.titlebar_text).copy(alpha = 0.5f),
            fontSize = 18.sp
        )
        if (isExpanded) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(R.string.action_collapse),
                tint = colorResource(R.color.titlebar_text),
                modifier = Modifier
                    .size(24.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onCollapse() }
            )
        }
    }
}

/**
 * Text field for entering agent commands.
 * Includes send button and processing state styling.
 */
@Composable
private fun AgentCommandTextField(
    command: String,
    onCommandChange: (String) -> Unit,
    isProcessing: Boolean,
    onSendCommand: () -> Unit,
    onFocusGained: () -> Unit,
    onFocusLost: () -> Unit
) {
    TextField(
        value = command,
        onValueChange = onCommandChange,
        enabled = !isProcessing,
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .onFocusChanged { focusState ->
                if (focusState.isFocused) {
                    onFocusGained()
                } else {
                    onFocusLost()
                }
            },
        trailingIcon = {
            IconButton(
                enabled = !isProcessing && command.isNotBlank(),
                onClick = {
                    if (command.isNotBlank()) {
                        onSendCommand()
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.action_send),
                    tint = if (isProcessing || command.isBlank()) Color.Gray else colorResource(R.color.titlebar_background)
                )
            }
        },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = if (isProcessing) Color.LightGray.copy(alpha = 0.3f) else Color.Transparent,
            unfocusedContainerColor = if (isProcessing) Color.LightGray.copy(alpha = 0.3f) else Color.Transparent,
            disabledContainerColor = Color.LightGray.copy(alpha = 0.3f)
        ),
        maxLines = 5,
        minLines = 5
    )
}

/**
 * Animated progress bar shown when agent is processing a command.
 */
@Composable
fun ProcessingIndicatorBar() {
    val infiniteTransition = rememberInfiniteTransition(label = "processing_animation")
    val offset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "gradient_offset"
    )

    val brush = Brush.linearGradient(
        colors = listOf(
            colorResource(R.color.titlebar_background),
            Color.White,
            colorResource(R.color.titlebar_background)
        ),
        start = Offset(x = offset * 1000f, y = 0f),
        end = Offset(x = offset * 1000f + 500f, y = 0f)
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(brush)
    )
}
