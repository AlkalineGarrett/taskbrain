package org.alkaline.taskbrain.ui.currentnote

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.alkaline.taskbrain.R
import org.alkaline.taskbrain.data.RecentTab

/**
 * A horizontal scrollable bar showing recently accessed note tabs.
 * Tabs are ordered by most recently accessed (left = most recent).
 */
@Composable
fun RecentTabsBar(
    tabs: List<RecentTab>,
    currentNoteId: String,
    onTabClick: (String) -> Unit,
    onTabClose: (String) -> Unit,
    notesNeedingFix: Set<String> = emptySet(),
    modifier: Modifier = Modifier
) {
    // Don't show if no tabs
    if (tabs.isEmpty()) {
        return
    }

    val listState = rememberLazyListState()

    // Scroll to the current tab whenever it moves (tab list reorder or note change)
    val currentTabIndex = tabs.indexOfFirst { it.noteId == currentNoteId }
    LaunchedEffect(currentTabIndex) {
        if (currentTabIndex >= 0) {
            listState.animateScrollToItem(currentTabIndex)
        }
    }

    LazyRow(
        state = listState,
        modifier = modifier
            .background(TabBarBackgroundColor)
            .padding(horizontal = TabBarHorizontalPadding, vertical = TabBarVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TabSpacing)
    ) {
        items(
            items = tabs,
            key = { tab -> tab.noteId }
        ) { tab ->
            val isCurrentTab = tab.noteId == currentNoteId
            TabItem(
                tab = tab,
                isCurrentTab = isCurrentTab,
                needsFix = tab.noteId in notesNeedingFix,
                onClick = { if (!isCurrentTab) onTabClick(tab.noteId) },
                onClose = { onTabClose(tab.noteId) },
                modifier = Modifier.animateItem(
                    placementSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium
                    ),
                    fadeInSpec = tween(durationMillis = 200),
                    fadeOutSpec = tween(durationMillis = 200)
                )
            )
        }
    }
}

@Composable
private fun TabItem(
    tab: RecentTab,
    isCurrentTab: Boolean,
    needsFix: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    val backgroundColor = if (isCurrentTab) {
        TabSelectedBackgroundColor
    } else {
        TabInactiveBackgroundColor
    }
    val textColor = Color.Black

    Box(
        modifier = modifier
            .height(TabHeight)
            .widthIn(max = TabMaxWidth)
            .clip(RoundedCornerShape(TabCornerRadius))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(start = TabContentPaddingStart, end = TabContentPaddingEnd),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (needsFix) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_warning),
                    contentDescription = stringResource(R.string.tab_needs_fix_indicator),
                    tint = colorResource(R.color.action_button_needs_fix_background),
                    modifier = Modifier.size(MenuIconSize)
                )
                androidx.compose.foundation.layout.Spacer(
                    modifier = Modifier.width(TabWarningIconSpacing)
                )
            }
            Text(
                text = tab.displayText.ifEmpty { stringResource(R.string.new_note) },
                color = textColor,
                fontSize = TabTextSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Box(modifier = Modifier.wrapContentSize(Alignment.TopEnd)) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.tab_menu),
                    tint = textColor.copy(alpha = 0.7f),
                    modifier = Modifier
                        .size(MenuIconSize)
                        .clickable { showMenu = true }
                )
                if (showMenu) {
                    DropdownMenu(
                        expanded = true,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.close_tab)) },
                            onClick = {
                                showMenu = false
                                onClose()
                            }
                        )
                    }
                }
            }
        }
    }
}

// Tab bar styling constants
private val TabBarBackgroundColor = Color(0xFFF5F5F5)
private val TabSelectedBackgroundColor = Color(0xFF9CC8F5)  // Matches text selection color
private val TabInactiveBackgroundColor = Color(0xFFE0E0E0)
private val TabBarHorizontalPadding = 8.dp
private val TabBarVerticalPadding = 4.dp
private val TabSpacing = 4.dp
private val TabHeight = 28.dp
private val TabMaxWidth = 72.dp
private val TabCornerRadius = 4.dp
private val TabTextSize = 12.sp
private val TabContentPaddingStart = 4.dp
private val TabContentPaddingEnd = 2.dp
private val MenuIconSize = 14.dp
private val TabWarningIconSpacing = 3.dp
