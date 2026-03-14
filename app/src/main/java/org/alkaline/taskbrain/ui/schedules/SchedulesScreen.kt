package org.alkaline.taskbrain.ui.schedules

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.alkaline.taskbrain.R
import org.alkaline.taskbrain.dsl.directives.EnrichedExecution
import org.alkaline.taskbrain.dsl.directives.Schedule
import org.alkaline.taskbrain.ui.components.ErrorDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Screen for viewing and managing scheduled directive executions.
 *
 * Contains three tabs:
 * - Last 24 Hours: History of executed schedules
 * - Next 24 Hours: Upcoming schedules
 * - Missed: Schedules that were too late to auto-execute
 */
@Composable
fun SchedulesScreen(
    onNavigateBack: () -> Unit = {},
    schedulesViewModel: SchedulesViewModel = viewModel()
) {
    val uiState by schedulesViewModel.uiState.collectAsState()
    val pagerState = rememberPagerState(pageCount = { 3 })
    val coroutineScope = rememberCoroutineScope()

    val tabs = listOf(
        stringResource(R.string.tab_last_24_hours),
        stringResource(R.string.tab_next_24_hours),
        stringResource(R.string.tab_missed)
    )

    // Refresh when screen becomes visible
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                schedulesViewModel.loadAllTabs()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Error dialog
    uiState.error?.let { error ->
        ErrorDialog(
            title = stringResource(R.string.error_title),
            message = error,
            onDismiss = { schedulesViewModel.clearError() }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Tabs
        TabRow(selectedTabIndex = pagerState.currentPage) {
            tabs.forEachIndexed { index, title ->
                val badgeCount = when (index) {
                    2 -> uiState.missed.size
                    else -> 0
                }
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(index)
                        }
                    },
                    text = {
                        if (badgeCount > 0) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(title)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "($badgeCount)",
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        } else {
                            Text(title)
                        }
                    }
                )
            }
        }

        // Pager content
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f)
        ) { page ->
            when (page) {
                0 -> Last24HoursTab(
                    executions = uiState.last24Hours,
                    isLoading = uiState.isLoading
                )
                1 -> Next24HoursTab(
                    schedules = uiState.next24Hours,
                    isLoading = uiState.isLoading
                )
                2 -> MissedTab(
                    executions = uiState.missed,
                    selectedIds = uiState.selectedMissedIds,
                    isLoading = uiState.isLoading,
                    isRunning = uiState.isRunning,
                    onToggleSelection = schedulesViewModel::toggleSelection,
                    onRunSelected = schedulesViewModel::runSelectedMissed,
                    onDismissSelected = schedulesViewModel::dismissSelectedMissed
                )
            }
        }
    }
}

@Composable
private fun Last24HoursTab(
    executions: List<EnrichedExecution>,
    isLoading: Boolean
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when {
            isLoading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            executions.isEmpty() -> {
                Text(
                    text = stringResource(R.string.no_history),
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(executions) { execution ->
                        ExecutionHistoryItem(execution)
                    }
                }
            }
        }
    }
}

@Composable
private fun Next24HoursTab(
    schedules: List<Schedule>,
    isLoading: Boolean
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when {
            isLoading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            schedules.isEmpty() -> {
                Text(
                    text = stringResource(R.string.no_upcoming),
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(schedules) { schedule ->
                        UpcomingScheduleItem(schedule)
                    }
                }
            }
        }
    }
}

@Composable
private fun MissedTab(
    executions: List<EnrichedExecution>,
    selectedIds: Set<String>,
    isLoading: Boolean,
    isRunning: Boolean,
    onToggleSelection: (String) -> Unit,
    onRunSelected: () -> Unit,
    onDismissSelected: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when {
            isLoading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            executions.isEmpty() -> {
                Text(
                    text = stringResource(R.string.no_missed),
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(executions) { execution ->
                            MissedExecutionItem(
                                execution = execution,
                                isSelected = execution.id in selectedIds,
                                onToggle = { onToggleSelection(execution.id) }
                            )
                        }
                    }

                    // Run and Dismiss buttons
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(16.dp),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                    ) {
                        // Dismiss button
                        androidx.compose.material3.OutlinedButton(
                            onClick = onDismissSelected,
                            enabled = selectedIds.isNotEmpty() && !isRunning,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = stringResource(R.string.dismiss_selected))
                        }

                        // Run button
                        Button(
                            onClick = onRunSelected,
                            enabled = selectedIds.isNotEmpty() && !isRunning,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.height(20.dp).width(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            } else {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text(text = stringResource(R.string.run_selected))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExecutionHistoryItem(execution: EnrichedExecution) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status icon
            Icon(
                imageVector = if (execution.success) Icons.Default.Check else Icons.Default.Close,
                contentDescription = if (execution.success) stringResource(R.string.schedule_success) else stringResource(R.string.schedule_failed),
                tint = if (execution.success) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                // Note name (primary identifier)
                Text(
                    text = execution.noteName.ifEmpty { stringResource(R.string.schedule_unknown_note) },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Note path (if set)
                if (execution.notePath.isNotEmpty()) {
                    Text(
                        text = execution.notePath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Directive source (truncated)
                if (execution.directiveSource.isNotEmpty()) {
                    Text(
                        text = execution.directiveSource.take(50) + if (execution.directiveSource.length > 50) "..." else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                execution.executedAt?.let { timestamp ->
                    val dateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                    Text(
                        text = dateFormat.format(timestamp.toDate()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (execution.manualRun) {
                    Text(
                        text = stringResource(R.string.schedule_manual_run),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                execution.error?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun UpcomingScheduleItem(schedule: Schedule) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = schedule.notePath.ifEmpty { stringResource(R.string.schedule_unknown) },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = schedule.displayDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                schedule.nextExecution?.let { timestamp ->
                    val now = System.currentTimeMillis()
                    val execTime = timestamp.toDate().time
                    val diffMs = execTime - now

                    val countdown = formatCountdown(diffMs)
                    Text(
                        text = stringResource(R.string.schedule_in_fmt, countdown),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun MissedExecutionItem(
    execution: EnrichedExecution,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggle() }
            )

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                // Note name (primary identifier)
                Text(
                    text = execution.noteName.ifEmpty { stringResource(R.string.schedule_unknown_note) },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Note path (if set)
                if (execution.notePath.isNotEmpty()) {
                    Text(
                        text = execution.notePath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Directive source (truncated)
                if (execution.directiveSource.isNotEmpty()) {
                    Text(
                        text = execution.directiveSource.take(50) + if (execution.directiveSource.length > 50) "..." else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                execution.scheduledFor?.let { timestamp ->
                    val dateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                    Text(
                        text = stringResource(R.string.schedule_was_due_fmt, dateFormat.format(timestamp.toDate())),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val now = System.currentTimeMillis()
                    val missedTime = timestamp.toDate().time
                    val missedAgo = formatCountdown(now - missedTime)
                    Text(
                        text = stringResource(R.string.schedule_missed_fmt, missedAgo),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

private fun formatCountdown(milliseconds: Long): String {
    val absMs = kotlin.math.abs(milliseconds)
    val hours = TimeUnit.MILLISECONDS.toHours(absMs)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(absMs) % 60

    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "<1m"
    }
}
