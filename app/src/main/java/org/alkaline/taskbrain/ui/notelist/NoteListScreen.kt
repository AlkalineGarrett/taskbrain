package org.alkaline.taskbrain.ui.notelist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.livedata.observeAsState
import kotlinx.coroutines.flow.SharedFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.firebase.Timestamp
import org.alkaline.taskbrain.R
import org.alkaline.taskbrain.data.ContentSnippet
import org.alkaline.taskbrain.data.FirestoreUsage
import org.alkaline.taskbrain.data.Note
import org.alkaline.taskbrain.data.NoteSearchResult
import org.alkaline.taskbrain.data.NoteSortMode
import org.alkaline.taskbrain.data.SearchHistoryEntry
import org.alkaline.taskbrain.data.SearchMatch
import org.alkaline.taskbrain.ui.components.ActionButton
import org.alkaline.taskbrain.ui.components.ActionButtonBar
import org.alkaline.taskbrain.ui.components.ErrorDialog
import android.text.format.DateFormat
import android.util.Log
import androidx.compose.ui.text.font.FontFamily
import java.util.Date

@Composable
fun NoteListScreen(
    noteListViewModel: NoteListViewModel = viewModel(),
    onNoteClick: (String) -> Unit = {},
    onSaveCompleted: SharedFlow<Unit>? = null
) {
    val notes by noteListViewModel.notes.observeAsState(emptyList())
    val deletedNotes by noteListViewModel.deletedNotes.observeAsState(emptyList())
    val loadStatus by noteListViewModel.loadStatus.observeAsState()
    val createNoteStatus by noteListViewModel.createNoteStatus.observeAsState()
    val sortMode by noteListViewModel.sortMode.observeAsState(NoteSortMode.RECENT)
    val noteStats by noteListViewModel.noteStatsLive.observeAsState(emptyMap())
    val searchState by noteListViewModel.searchState.collectAsState()
    val activeSearchResults by noteListViewModel.activeSearchResults.observeAsState(emptyList())
    val deletedSearchResults by noteListViewModel.deletedSearchResults.observeAsState(emptyList())
    val searchHistory by noteListViewModel.searchHistory.observeAsState(emptyList())

    var usageReport by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        noteListViewModel.loadNotes()
        noteListViewModel.loadSearchHistory()
    }

    // Refresh when a note is saved from CurrentNoteScreen (silent refresh, no loading indicator)
    LaunchedEffect(onSaveCompleted) {
        onSaveCompleted?.collect {
            noteListViewModel.refreshNotes()
        }
    }

    // Show error dialogs
    if (loadStatus is LoadStatus.Error) {
        ErrorDialog(
            title = stringResource(R.string.error_load),
            throwable = (loadStatus as LoadStatus.Error).throwable,
            onDismiss = { noteListViewModel.clearLoadError() }
        )
    }

    if (createNoteStatus is CreateNoteStatus.Error) {
        ErrorDialog(
            title = stringResource(R.string.error_create_note),
            throwable = (createNoteStatus as CreateNoteStatus.Error).throwable,
            onDismiss = { noteListViewModel.clearCreateNoteError() }
        )
    }

    usageReport?.let { report ->
        FirestoreUsageDialog(
            report = report,
            onClose = { usageReport = null },
            onReset = {
                FirestoreUsage.reset()
                usageReport = FirestoreUsage.getReport()
            },
        )
    }

    Scaffold(
        topBar = {
            Column {
                NoteListTopBar(
                    onAddNoteClick = {
                        noteListViewModel.createNote(onSuccess = { noteId ->
                            onNoteClick(noteId)
                        })
                    },
                    onRefreshClick = { noteListViewModel.loadNotes() },
                    onSearchClick = { noteListViewModel.toggleSearch() },
                    onUsageClick = {
                        val report = FirestoreUsage.getReport()
                        Log.i("FirestoreUsage", "\n$report")
                        usageReport = report
                    },
                )
                if (!searchState.isSearchOpen) {
                    SortModeRow(
                        selected = sortMode,
                        onModeSelected = { noteListViewModel.setSortMode(it) },
                    )
                }
                if (searchState.isSearchOpen) {
                    SearchPanel(
                        searchState = searchState,
                        searchHistory = searchHistory,
                        onQueryChange = { noteListViewModel.updateSearchQuery(it) },
                        onSearchByNameChange = { noteListViewModel.setSearchByName(it) },
                        onSearchByContentChange = { noteListViewModel.setSearchByContent(it) },
                        onGoClick = { noteListViewModel.executeSearch() },
                        onHistorySelect = { noteListViewModel.replaySearch(it) },
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (loadStatus) {
                is LoadStatus.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                is LoadStatus.Error -> {
                    Text(
                        text = stringResource(R.string.error_an_error_occurred),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                else -> {
                    if (searchState.isSearchOpen && searchState.query.isNotEmpty()) {
                        SearchResultsList(
                            activeResults = activeSearchResults,
                            deletedResults = deletedSearchResults,
                            query = searchState.query,
                            onNoteClick = onNoteClick,
                            onDelete = { noteListViewModel.softDeleteNote(it) },
                            onRestore = { noteListViewModel.undeleteNote(it) },
                        )
                    } else if (notes.isEmpty() && deletedNotes.isEmpty() && loadStatus is LoadStatus.Success) {
                        Text(
                            text = stringResource(R.string.no_notes_found),
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else if (!searchState.isSearchOpen || searchState.query.isEmpty()) {
                        LazyColumn(
                            contentPadding = PaddingValues(top = 0.dp, start = 16.dp, end = 16.dp, bottom = 16.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(notes) { note ->
                                NoteItem(
                                    note = note,
                                    lastViewed = noteStats[note.id]?.lastAccessedAt,
                                    onClick = { onNoteClick(note.id) },
                                    onDelete = { noteListViewModel.softDeleteNote(note.id) }
                                )
                                HorizontalDivider()
                            }

                            if (deletedNotes.isNotEmpty()) {
                                item {
                                    DeletedNotesHeader()
                                }
                                items(deletedNotes) { note ->
                                    NoteItem(
                                        note = note,
                                        lastViewed = noteStats[note.id]?.lastAccessedAt,
                                        onClick = { onNoteClick(note.id) },
                                        isDeleted = true,
                                        onRestore = { noteListViewModel.undeleteNote(note.id) }
                                    )
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            }

            // Show loading overlay when creating a note
            if (createNoteStatus is CreateNoteStatus.Loading) {
                 CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortModeRow(
    selected: NoteSortMode,
    onModeSelected: (NoteSortMode) -> Unit,
) {
    val modes = listOf(
        NoteSortMode.RECENT to R.string.sort_recent,
        NoteSortMode.FREQUENT to R.string.sort_frequent,
        NoteSortMode.CONSISTENT to R.string.sort_consistent,
    )
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        modes.forEachIndexed { index, (mode, labelRes) ->
            SegmentedButton(
                selected = selected == mode,
                onClick = { onModeSelected(mode) },
                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
            ) {
                Text(stringResource(labelRes))
            }
        }
    }
}

@Composable
fun NoteListTopBar(
    onAddNoteClick: () -> Unit,
    onRefreshClick: () -> Unit,
    onSearchClick: () -> Unit,
    onUsageClick: () -> Unit,
) {
    ActionButtonBar(
        modifier = Modifier,
        content = {
            ActionButton(
                text = stringResource(R.string.action_add_note),
                icon = Icons.Filled.Add,
                onClick = onAddNoteClick
            )
            Spacer(modifier = Modifier.width(8.dp))
            ActionButton(
                text = stringResource(R.string.action_search),
                icon = Icons.Filled.Search,
                onClick = onSearchClick
            )
            Spacer(modifier = Modifier.width(8.dp))
            ActionButton(
                text = stringResource(R.string.action_refresh),
                icon = Icons.Filled.Refresh,
                onClick = onRefreshClick
            )
            Spacer(modifier = Modifier.width(8.dp))
            ActionButton(
                text = stringResource(R.string.action_firestore_usage),
                icon = Icons.Filled.Assessment,
                onClick = onUsageClick
            )
        }
    )
}

@Composable
private fun FirestoreUsageDialog(
    report: String,
    onClose: () -> Unit,
    onReset: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.firestore_usage_title)) },
        text = {
            // Selectable so the user can long-press and copy the report out.
            SelectionContainer {
                Text(
                    text = report,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) {
                Text(stringResource(R.string.firestore_usage_close))
            }
        },
        dismissButton = {
            TextButton(onClick = onReset) {
                Text(stringResource(R.string.firestore_usage_reset))
            }
        },
    )
}

@Composable
fun SearchPanel(
    searchState: SearchState,
    searchHistory: List<SearchHistoryEntry>,
    onQueryChange: (String) -> Unit,
    onSearchByNameChange: (Boolean) -> Unit,
    onSearchByContentChange: (Boolean) -> Unit,
    onGoClick: () -> Unit,
    onHistorySelect: (SearchHistoryEntry) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var showHistory by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF5F5F5))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                    value = searchState.query,
                    onValueChange = onQueryChange,
                    placeholder = { Text(stringResource(R.string.search_hint)) },
                    singleLine = true,
                    trailingIcon = {
                        if (searchHistory.isNotEmpty()) {
                            IconButton(onClick = { showHistory = !showHistory }) {
                                Text(
                                    text = "\u25BE",
                                    fontSize = 18.sp,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
                DropdownMenu(
                    expanded = showHistory,
                    onDismissRequest = { showHistory = false },
                ) {
                    for (entry in searchHistory) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text = entry.query,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    val tags = entry.criteria
                                        .filter { it.value }
                                        .keys
                                        .joinToString(", ") { it.replaceFirstChar { c -> c.uppercase() } }
                                    if (tags.isNotEmpty()) {
                                        Text(
                                            text = tags,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                    }
                                }
                            },
                            onClick = {
                                showHistory = false
                                onHistorySelect(entry)
                            },
                        )
                    }
                }
            }
            if (searchState.query.isNotEmpty()) {
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onGoClick) {
                    Text(stringResource(R.string.search_go))
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = searchState.searchByName,
                onCheckedChange = onSearchByNameChange,
            )
            Text(
                text = stringResource(R.string.search_filter_name),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.width(16.dp))
            Checkbox(
                checked = searchState.searchByContent,
                onCheckedChange = onSearchByContentChange,
            )
            Text(
                text = stringResource(R.string.search_filter_content),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
fun SearchResultsList(
    activeResults: List<NoteSearchResult>,
    deletedResults: List<NoteSearchResult>,
    query: String,
    onNoteClick: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRestore: (String) -> Unit,
) {
    if (activeResults.isEmpty() && deletedResults.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.search_no_results),
                color = MaterialTheme.colorScheme.outline,
            )
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(top = 0.dp, start = 16.dp, end = 16.dp, bottom = 16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(activeResults) { result ->
            SearchResultItem(
                result = result,
                query = query,
                onClick = { onNoteClick(result.note.id) },
                onDelete = { onDelete(result.note.id) },
            )
            HorizontalDivider()
        }

        if (deletedResults.isNotEmpty()) {
            item { DeletedNotesHeader() }
            items(deletedResults) { result ->
                SearchResultItem(
                    result = result,
                    query = query,
                    onClick = { onNoteClick(result.note.id) },
                    isDeleted = true,
                    onRestore = { onRestore(result.note.id) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun SearchResultItem(
    result: NoteSearchResult,
    query: String,
    onClick: () -> Unit,
    isDeleted: Boolean = false,
    onDelete: (() -> Unit)? = null,
    onRestore: (() -> Unit)? = null,
) {
    var showMenu by remember { mutableStateOf(false) }
    val firstLine = result.note.content.lines().firstOrNull() ?: ""
    val timestamp = result.note.updatedAt

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = highlightMatches(
                    firstLine.ifEmpty { stringResource(R.string.empty_note) },
                    if (firstLine.isNotEmpty()) result.nameMatches else emptyList(),
                ),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isDeleted) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )

            if (timestamp != null) {
                Text(
                    text = formatTimestamp(timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }

            Box(modifier = Modifier.wrapContentSize(Alignment.TopEnd)) {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.action_more_options),
                        modifier = Modifier.size(20.dp),
                        tint = colorResource(R.color.icon_default)
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    if (isDeleted && onRestore != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_restore_note)) },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_restore),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            onClick = { showMenu = false; onRestore() }
                        )
                    } else if (!isDeleted && onDelete != null) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.action_delete_note),
                                    color = colorResource(R.color.menu_danger_text)
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_delete),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = colorResource(R.color.menu_danger_text)
                                )
                            },
                            onClick = { showMenu = false; onDelete() }
                        )
                    }
                }
            }
        }

        for (snippet in result.contentSnippets) {
            ContentSnippetView(snippet = snippet)
        }
    }
}

@Composable
fun ContentSnippetView(snippet: ContentSnippet) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, top = 4.dp, bottom = 4.dp)
            .background(Color(0xFFF0F0F0), RoundedCornerShape(4.dp))
            .padding(8.dp)
    ) {
        for (line in snippet.lines) {
            val lineMatches = snippet.matches.filter { it.lineIndex == line.lineIndex }
            Text(
                text = highlightMatches(line.text, lineMatches),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun highlightMatches(
    text: String,
    matches: List<SearchMatch>,
) = buildAnnotatedString {
    if (matches.isEmpty()) {
        append(text)
        return@buildAnnotatedString
    }
    var cursor = 0
    val sorted = matches.sortedBy { it.matchStart }
    for (match in sorted) {
        if (match.matchStart > cursor) {
            append(text.substring(cursor, match.matchStart))
        }
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(text.substring(match.matchStart, minOf(match.matchEnd, text.length)))
        }
        cursor = minOf(match.matchEnd, text.length)
    }
    if (cursor < text.length) {
        append(text.substring(cursor))
    }
}

@Composable
fun NoteItem(
    note: Note,
    lastViewed: Timestamp? = null,
    onClick: () -> Unit,
    isDeleted: Boolean = false,
    onDelete: (() -> Unit)? = null,
    onRestore: (() -> Unit)? = null
) {
    var showMenu by remember { mutableStateOf(false) }
    val firstLine = note.content.lines().firstOrNull() ?: ""

    val timestamp = lastViewed ?: note.updatedAt

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = firstLine.ifEmpty { stringResource(R.string.empty_note) },
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (isDeleted) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )

        if (timestamp != null) {
            Text(
                text = formatTimestamp(timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }

        Box(modifier = Modifier.wrapContentSize(Alignment.TopEnd)) {
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.action_more_options),
                    modifier = Modifier.size(20.dp),
                    tint = colorResource(R.color.icon_default)
                )
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                if (isDeleted && onRestore != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_restore_note)) },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_restore),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        onClick = {
                            showMenu = false
                            onRestore()
                        }
                    )
                } else if (!isDeleted && onDelete != null) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.action_delete_note),
                                color = colorResource(R.color.menu_danger_text)
                            )
                        },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = colorResource(R.color.menu_danger_text)
                            )
                        },
                        onClick = {
                            showMenu = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun DeletedNotesHeader() {
    Text(
        text = stringResource(R.string.section_deleted_notes),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
    )
}

@Composable
private fun formatTimestamp(timestamp: Timestamp): String {
    val context = androidx.compose.ui.platform.LocalContext.current
    val date = timestamp.toDate()
    val dateStr = DateFormat.getMediumDateFormat(context).format(date)
    val timeStr = DateFormat.getTimeFormat(context).format(date)
    return "$dateStr, $timeStr"
}
