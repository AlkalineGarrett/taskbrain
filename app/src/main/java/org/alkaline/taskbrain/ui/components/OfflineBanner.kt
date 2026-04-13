package org.alkaline.taskbrain.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.alkaline.taskbrain.R

/**
 * Banner that appears when the device goes offline and briefly when it reconnects.
 * Returns whether a collapsed offline icon should be shown in the status bar.
 */
@Composable
fun OfflineBanner(
    isOnline: Boolean,
    onCollapsedStateChange: (showOfflineIcon: Boolean) -> Unit
) {
    var showOfflineBanner by remember { mutableStateOf(false) }
    var showSyncedBanner by remember { mutableStateOf(false) }
    var wasOnline by remember { mutableStateOf(isOnline) }

    LaunchedEffect(isOnline) {
        val previouslyOnline = wasOnline
        wasOnline = isOnline
        when {
            !isOnline && previouslyOnline -> {
                showSyncedBanner = false
                showOfflineBanner = true
                onCollapsedStateChange(false)
                delay(COLLAPSE_DELAY_MS)
                showOfflineBanner = false
                onCollapsedStateChange(true)
            }
            !isOnline && !previouslyOnline -> {
                onCollapsedStateChange(true)
            }
            isOnline && !previouslyOnline -> {
                showOfflineBanner = false
                onCollapsedStateChange(false)
                showSyncedBanner = true
                delay(SYNCED_DISPLAY_MS)
                showSyncedBanner = false
            }
        }
    }

    BannerBar(
        visible = showOfflineBanner,
        icon = painterResource(R.drawable.ic_cloud_off),
        text = stringResource(R.string.offline_banner_message),
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    )

    BannerBar(
        visible = showSyncedBanner,
        icon = painterResource(R.drawable.ic_cloud_done),
        text = stringResource(R.string.synced_banner_message),
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    )
}

@Composable
private fun BannerBar(
    visible: Boolean,
    icon: Painter,
    text: String,
    containerColor: Color,
    contentColor: Color
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(),
        exit = shrinkVertically()
    ) {
        Surface(color = containerColor, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(painter = icon, contentDescription = null, tint = contentColor)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = text, color = contentColor, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private const val COLLAPSE_DELAY_MS = 5000L
private const val SYNCED_DISPLAY_MS = 3000L
