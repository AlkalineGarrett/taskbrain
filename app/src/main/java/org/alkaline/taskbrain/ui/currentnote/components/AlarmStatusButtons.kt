package org.alkaline.taskbrain.ui.currentnote.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.alkaline.taskbrain.R
import org.alkaline.taskbrain.data.AlarmStatus

private val StatusButtonFontSize = 12.sp
private val StatusButtonPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
private val NavButtonFontSize = 12.sp
private val NavButtonPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
private val NavButtonWidth = 32.dp

/**
 * Header row for an existing alarm: optional prev/next navigation framing the
 * Reopen / Skip / Done state-mutation buttons. Each mutation triggers `onDismiss`
 * after the callback so the dialog closes on action.
 */
@Composable
internal fun AlarmStatusButtons(
    status: AlarmStatus,
    onMarkDone: (() -> Unit)?,
    onMarkCancelled: (() -> Unit)?,
    onReactivate: (() -> Unit)?,
    onNavigatePrevious: (() -> Unit)?,
    onNavigateNext: (() -> Unit)?,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onDismiss: () -> Unit
) {
    val showNavigation = onNavigatePrevious != null || onNavigateNext != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showNavigation) {
            OutlinedButton(
                onClick = { onNavigatePrevious?.invoke() },
                enabled = hasPrevious,
                modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 1.dp).width(NavButtonWidth),
                contentPadding = NavButtonPadding
            ) {
                Text("<", fontSize = NavButtonFontSize)
            }
        }
        OutlinedButton(
            onClick = {
                onReactivate?.invoke()
                onDismiss()
            },
            enabled = status != AlarmStatus.PENDING,
            modifier = Modifier.weight(1f),
            contentPadding = StatusButtonPadding
        ) {
            Text(stringResource(R.string.alarm_reopen), fontSize = StatusButtonFontSize, maxLines = 1)
        }
        OutlinedButton(
            onClick = {
                onMarkCancelled?.invoke()
                onDismiss()
            },
            enabled = status != AlarmStatus.CANCELLED,
            modifier = Modifier.weight(1f),
            contentPadding = StatusButtonPadding
        ) {
            Text(stringResource(R.string.alarm_skip), fontSize = StatusButtonFontSize, maxLines = 1)
        }
        Button(
            onClick = {
                onMarkDone?.invoke()
                onDismiss()
            },
            enabled = status != AlarmStatus.DONE,
            modifier = Modifier.weight(1f),
            contentPadding = StatusButtonPadding,
            colors = ButtonDefaults.buttonColors(
                containerColor = colorResource(R.color.action_button_background),
                contentColor = colorResource(R.color.action_button_text)
            )
        ) {
            Text(stringResource(R.string.alarm_done), fontSize = StatusButtonFontSize, maxLines = 1)
        }
        if (showNavigation) {
            OutlinedButton(
                onClick = { onNavigateNext?.invoke() },
                enabled = hasNext,
                modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 1.dp).width(NavButtonWidth),
                contentPadding = NavButtonPadding
            ) {
                Text(">", fontSize = NavButtonFontSize)
            }
        }
    }
}
