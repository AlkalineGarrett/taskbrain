package org.alkaline.taskbrain.ui.currentnote

import android.util.Log
import androidx.compose.foundation.background
import org.alkaline.taskbrain.data.Alarm
import org.alkaline.taskbrain.data.AlarmStatus
import org.alkaline.taskbrain.data.CloseTabResult
import org.alkaline.taskbrain.data.TabState
import org.alkaline.taskbrain.ui.currentnote.rendering.ButtonCallbacks
import org.alkaline.taskbrain.ui.currentnote.util.AlarmSymbolUtils
import org.alkaline.taskbrain.ui.currentnote.util.TappableSymbol
import org.alkaline.taskbrain.ui.currentnote.util.ClipboardHtmlConverter
import org.alkaline.taskbrain.ui.currentnote.util.CompletedLineUtils
import org.alkaline.taskbrain.ui.currentnote.undo.UndoStatePersistence
import androidx.compose.ui.res.stringResource
import org.alkaline.taskbrain.R
import org.alkaline.taskbrain.ui.currentnote.util.TextLineUtils
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.StateFlow
import org.alkaline.taskbrain.dsl.runtime.MutationType
import org.alkaline.taskbrain.ui.currentnote.components.AgentCommandSection
import org.alkaline.taskbrain.ui.currentnote.components.CommandBar
import org.alkaline.taskbrain.ui.currentnote.components.NoteTextField
import org.alkaline.taskbrain.ui.currentnote.components.StatusBar
import androidx.compose.runtime.rememberUpdatedState

/**
 * Main screen for viewing and editing a note.
 * Coordinates the note text field, command bar, and agent command section.
 */
@Composable
fun CurrentNoteScreen(
    noteId: String? = null,
    isFingerDownFlow: StateFlow<Boolean>? = null,
    onNavigateBack: () -> Unit = {},
    onNavigateToNote: (String) -> Unit = {},
    currentNoteViewModel: CurrentNoteViewModel = viewModel(),
    recentTabsViewModel: RecentTabsViewModel = viewModel()
) {
    // --- ViewModel state observations ---
    val saveStatus by currentNoteViewModel.saveStatus.observeAsState()
    val loadStatus by currentNoteViewModel.loadStatus.observeAsState()
    val contentModified by currentNoteViewModel.contentModified.observeAsState(false)
    val isAgentProcessing by currentNoteViewModel.isAgentProcessing.observeAsState(false)
    val alarmCreated by currentNoteViewModel.alarmCreated.observeAsState()
    val alarmError by currentNoteViewModel.alarmError.observeAsState()
    val alarmPermissionWarning by currentNoteViewModel.alarmPermissionWarning.observeAsState(false)
    val notificationPermissionWarning by currentNoteViewModel.notificationPermissionWarning.observeAsState(false)
    val schedulingWarning by currentNoteViewModel.schedulingWarning.observeAsState()
    val isAlarmOperationPending by currentNoteViewModel.isAlarmOperationPending.observeAsState(false)
    val redoRollbackWarning by currentNoteViewModel.redoRollbackWarning.observeAsState()
    val isNoteDeletedFromVm by currentNoteViewModel.isNoteDeleted.observeAsState(false)
    val showCompleted by currentNoteViewModel.showCompleted.observeAsState(true)
    val directiveResultsRaw by currentNoteViewModel.directiveResults.observeAsState(emptyMap())
    val directiveResults = remember(directiveResultsRaw) {
        currentNoteViewModel.getResultsByPosition()
    }
    val buttonExecutionStates by currentNoteViewModel.buttonExecutionStates.observeAsState(emptyMap())
    val buttonErrors by currentNoteViewModel.buttonErrors.observeAsState(emptyMap())
    LaunchedEffect(directiveResultsRaw) {
        val firstContent = directiveResultsRaw.values.firstOrNull()?.toValue()?.toDisplayString()?.take(60)?.replace("\n", "\\n")
        Log.d("InlineEditCache", ">>> directiveResultsRaw CHANGED: ${directiveResultsRaw.size} entries, firstContent='$firstContent...'")
    }
    val recentTabs by recentTabsViewModel.tabs.observeAsState(emptyList())
    val tabsError by recentTabsViewModel.error.observeAsState()

    // --- Local state ---
    var displayedNoteId by remember { mutableStateOf(noteId) }
    LaunchedEffect(noteId) {
        if (noteId != displayedNoteId) displayedNoteId = noteId
    }

    val cachedContent = remember(displayedNoteId) {
        displayedNoteId?.let { recentTabsViewModel.getCachedContent(it) }
    }
    val initialContent = cachedContent?.noteLines?.joinToString("\n") { it.content } ?: ""
    val initialIsDeleted = cachedContent?.isDeleted ?: false

    var isNoteDeleted by remember(displayedNoteId) { mutableStateOf(initialIsDeleted) }
    LaunchedEffect(isNoteDeletedFromVm) { isNoteDeleted = isNoteDeletedFromVm }

    var userContent by remember(displayedNoteId) { mutableStateOf(initialContent) }
    var textFieldValue by remember(displayedNoteId) {
        mutableStateOf(TextFieldValue(initialContent, TextRange(initialContent.length)))
    }
    var isSaved by remember(displayedNoteId) { mutableStateOf(true) }
    var agentCommand by remember { mutableStateOf("") }
    var isAgentSectionExpanded by remember { mutableStateOf(false) }

    val mainContentFocusRequester = remember { FocusRequester() }
    var isMainContentFocused by remember { mutableStateOf(false) }

    val editorState = remember(displayedNoteId) {
        EditorState().apply {
            if (initialContent.isNotEmpty()) updateFromText(initialContent)
        }
    }
    val controller = rememberEditorController(editorState)

    // Update hidden indices for move system when showCompleted or lines change
    controller.hiddenIndices = remember(userContent, showCompleted) {
        CompletedLineUtils.computeHiddenIndices(editorState.lines.map { it.text }, showCompleted)
    }

    // Inline editing state for view directives
    val inlineEditState = rememberInlineEditState()
    LaunchedEffect(inlineEditState) {
        inlineEditState.onExecuteDirectives = { content, onResults ->
            currentNoteViewModel.executeDirectivesForContent(content, onResults)
        }
        inlineEditState.onExecuteSingleDirective = { sourceText, onResult ->
            currentNoteViewModel.executeSingleDirective(sourceText, onResult)
        }
        inlineEditState.onDirectiveEditConfirm = { _, _, _, _ ->
            inlineEditState.activeSession?.let { session ->
                currentNoteViewModel.saveInlineNoteContent(
                    noteId = session.noteId,
                    newContent = session.currentContent,
                    onSuccess = {
                        recentTabsViewModel.invalidateCache(session.noteId)
                        currentNoteViewModel.executeDirectivesForContent(session.currentContent) { results ->
                            session.updateDirectiveResults(results)
                            currentNoteViewModel.forceRefreshAllDirectives(userContent) {}
                        }
                    }
                )
            }
        }
    }

    val activeController = inlineEditState.activeController ?: controller
    val context = LocalContext.current
    val currentNoteId by currentNoteViewModel.currentNoteIdLiveData.observeAsState()

    LaunchedEffect(currentNoteId) {
        if (displayedNoteId == null && currentNoteId != null) displayedNoteId = currentNoteId
    }

    // Undo/redo state observation
    @Suppress("UNUSED_VARIABLE")
    val editorStateVersion = editorState.stateVersion
    @Suppress("UNUSED_VARIABLE")
    val undoStateVersion = controller.undoManager.stateVersion
    val canUndo = controller.canUndo
    val canRedo = controller.canRedo

    // Alarm dialog state
    var showAlarmDialog by remember { mutableStateOf(false) }
    var alarmDialogLineContent by remember { mutableStateOf("") }
    var alarmDialogLineIndex by remember { mutableStateOf<Int?>(null) }
    var alarmDialogSymbolIndex by remember { mutableStateOf(0) }
    val lineAlarms by currentNoteViewModel.lineAlarms.observeAsState(emptyList())
    val noteAlarmsForOverlay by currentNoteViewModel.noteAlarms.observeAsState(emptyMap())
    // When navigating between recurring instances, this overrides the line-based alarm
    var alarmDialogOverride by remember { mutableStateOf<Alarm?>(null) }
    val alarmDialogExistingAlarm = alarmDialogOverride ?: lineAlarms
        .sortedWith(compareByDescending<Alarm> { it.status == AlarmStatus.PENDING }
            .thenBy { it.createdAt?.toDate()?.time ?: 0L })
        .getOrNull(alarmDialogSymbolIndex)
    val alarmDialogRecurrenceConfig by currentNoteViewModel.recurrenceConfig.observeAsState()

    // Sibling instances for recurring alarm navigation in the dialog
    var alarmDialogSiblings by remember { mutableStateOf<List<Alarm>>(emptyList()) }
    val alarmDialogRecurringId = alarmDialogExistingAlarm?.recurringAlarmId
    LaunchedEffect(alarmDialogRecurringId, showAlarmDialog) {
        alarmDialogSiblings = if (showAlarmDialog && alarmDialogRecurringId != null) {
            currentNoteViewModel.getInstancesForRecurring(alarmDialogRecurringId)
        } else {
            emptyList()
        }
    }

    // --- Effects ---
    LifecycleAutoSaveEffect(
        controller = controller,
        currentNoteViewModel = currentNoteViewModel,
        recentTabsViewModel = recentTabsViewModel,
        userContent = userContent,
        isSaved = isSaved,
        isNoteDeleted = isNoteDeleted,
        currentNoteId = currentNoteId
    )

    DataLoadingEffects(
        displayedNoteId = displayedNoteId,
        loadStatus = loadStatus,
        tabsError = tabsError,
        userContent = userContent,
        editorState = editorState,
        controller = controller,
        currentNoteViewModel = currentNoteViewModel,
        recentTabsViewModel = recentTabsViewModel,
        onContentLoaded = { loadedContent ->
            userContent = loadedContent
            textFieldValue = TextFieldValue(loadedContent, TextRange(loadedContent.length))
        }
    )

    ContentSyncEffects(
        currentNoteId = currentNoteId,
        loadStatus = loadStatus,
        saveStatus = saveStatus,
        contentModified = contentModified,
        alarmCreated = alarmCreated,
        userContent = userContent,
        isNoteDeleted = isNoteDeleted,
        editorState = editorState,
        controller = controller,
        currentNoteViewModel = currentNoteViewModel,
        recentTabsViewModel = recentTabsViewModel,
        onContentChanged = { userContent = it },
        onSavedChanged = { isSaved = it }
    )

    DirectiveMutationEffect(
        currentNoteId = currentNoteId,
        userContent = userContent,
        editorState = editorState,
        controller = controller,
        currentNoteViewModel = currentNoteViewModel,
        onContentChanged = { content, tfv ->
            userContent = content
            textFieldValue = tfv
        },
        onMarkUnsaved = { isSaved = false }
    )

    // --- Dialogs ---
    NoteScreenDialogs(
        saveStatus = saveStatus,
        loadStatus = loadStatus,
        tabsError = tabsError,
        alarmError = alarmError,
        alarmPermissionWarning = alarmPermissionWarning,
        notificationPermissionWarning = notificationPermissionWarning,
        schedulingWarning = schedulingWarning,
        redoRollbackWarning = redoRollbackWarning,
        onClearSaveError = { currentNoteViewModel.clearSaveError() },
        onClearLoadError = { currentNoteViewModel.clearLoadError() },
        onClearTabsError = { recentTabsViewModel.clearError() },
        onClearAlarmError = { currentNoteViewModel.clearAlarmError() },
        onClearAlarmPermissionWarning = { currentNoteViewModel.clearAlarmPermissionWarning() },
        onClearNotificationPermissionWarning = { currentNoteViewModel.clearNotificationPermissionWarning() },
        onClearSchedulingWarning = { currentNoteViewModel.clearSchedulingWarning() },
        onClearRedoRollbackWarning = { currentNoteViewModel.clearRedoRollbackWarning() },
        showAlarmDialog = showAlarmDialog,
        alarmDialogLineContent = alarmDialogLineContent,
        alarmDialogExistingAlarm = alarmDialogExistingAlarm,
        alarmDialogRecurrenceConfig = alarmDialogRecurrenceConfig,
        onAlarmSave = { dueTime, stages ->
            val existing = alarmDialogExistingAlarm
            if (existing != null) {
                currentNoteViewModel.updateAlarm(existing, dueTime, stages)
            } else {
                currentNoteViewModel.saveAndCreateAlarm(userContent, alarmDialogLineContent, alarmDialogLineIndex, dueTime, stages)
            }
        },
        onAlarmSaveRecurring = { dueTime, stages, recurrenceConfig ->
            val existing = alarmDialogExistingAlarm
            if (existing != null) {
                currentNoteViewModel.updateRecurringAlarm(existing, dueTime, stages, recurrenceConfig)
            } else {
                currentNoteViewModel.saveAndCreateRecurringAlarm(userContent, alarmDialogLineContent, alarmDialogLineIndex, dueTime, stages, recurrenceConfig)
            }
        },
        onAlarmMarkDone = alarmDialogExistingAlarm?.let { alarm -> { currentNoteViewModel.markAlarmDone(alarm.id) } },
        onAlarmMarkCancelled = alarmDialogExistingAlarm?.let { alarm -> { currentNoteViewModel.cancelAlarm(alarm.id) } },
        onAlarmReactivate = alarmDialogExistingAlarm?.let { alarm -> { currentNoteViewModel.reactivateAlarm(alarm.id) } },
        onAlarmDelete = alarmDialogExistingAlarm?.let { alarm -> { currentNoteViewModel.deleteAlarmPermanently(alarm.id) } },
        onAlarmNavigatePrevious = if (alarmDialogRecurringId != null) {{
            val idx = alarmDialogSiblings.indexOfFirst { it.id == alarmDialogExistingAlarm?.id }
            if (idx > 0) alarmDialogOverride = alarmDialogSiblings[idx - 1]
        }} else null,
        onAlarmNavigateNext = if (alarmDialogRecurringId != null) {{
            val idx = alarmDialogSiblings.indexOfFirst { it.id == alarmDialogExistingAlarm?.id }
            if (idx in 0 until alarmDialogSiblings.lastIndex) alarmDialogOverride = alarmDialogSiblings[idx + 1]
        }} else null,
        alarmHasPrevious = alarmDialogSiblings.indexOfFirst { it.id == alarmDialogExistingAlarm?.id } > 0,
        alarmHasNext = alarmDialogSiblings.indexOfFirst { it.id == alarmDialogExistingAlarm?.id }.let { it in 0 until alarmDialogSiblings.lastIndex },
        onAlarmDismiss = {
            showAlarmDialog = false
            alarmDialogLineIndex = null
            alarmDialogOverride = null
            currentNoteViewModel.fetchRecurrenceConfig(null)
        },
        onFetchRecurrenceConfig = { currentNoteViewModel.fetchRecurrenceConfig(it) }
    )

    // Clipboard HTML formatting
    ClipboardHtmlConverter()

    // Text field value change handler
    val updateTextFieldValue: (TextFieldValue) -> Unit = { newValue ->
        val oldText = textFieldValue.text
        textFieldValue = newValue
        if (newValue.text != userContent) {
            userContent = newValue.text
            if (isSaved) isSaved = false
            val oldBracketCount = oldText.count { it == ']' }
            val newBracketCount = newValue.text.count { it == ']' }
            if (newBracketCount > oldBracketCount) {
                currentNoteViewModel.executeDirectivesLive(newValue.text)
            }
        }
    }

    // --- UI Composition ---
    val deletedNoteBackground = colorResource(R.color.deleted_note_background)
    val deletedNoteTextColor = colorResource(R.color.deleted_note_text)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isNoteDeleted) deletedNoteBackground else Color.White)
    ) {
        RecentTabsBar(
            tabs = recentTabs,
            currentNoteId = displayedNoteId ?: "",
            onTabClick = { targetNoteId ->
                if (!isSaved && userContent.isNotEmpty()) {
                    currentNoteViewModel.saveContent(userContent)
                }
                displayedNoteId = targetNoteId
            },
            onTabClose = { targetNoteId ->
                recentTabsViewModel.closeTab(targetNoteId)
                when (val result = TabState.closeTabNavigationTarget(recentTabs, targetNoteId, displayedNoteId ?: "")) {
                    is CloseTabResult.StayOnCurrent -> { /* no-op */ }
                    is CloseTabResult.NavigateBack -> onNavigateBack()
                    is CloseTabResult.SwitchTo -> { displayedNoteId = result.noteId }
                }
            }
        )

        NoteStatusBar(
            isSaved = isSaved,
            canUndo = canUndo && !isAlarmOperationPending,
            canRedo = canRedo && !isAlarmOperationPending,
            isNoteDeleted = isNoteDeleted,
            userContent = userContent,
            editorState = editorState,
            controller = controller,
            currentNoteViewModel = currentNoteViewModel,
            onContentChanged = { userContent = it },
            onMarkUnsaved = { isSaved = false },
            showCompleted = showCompleted,
            onShowCompletedToggle = { currentNoteViewModel.toggleShowCompleted() },
            onDeleteNote = {
                currentNoteViewModel.deleteCurrentNote(onSuccess = {
                    recentTabsViewModel.closeTab(displayedNoteId ?: "")
                    when (val result = TabState.closeTabNavigationTarget(recentTabs, displayedNoteId ?: "", displayedNoteId ?: "")) {
                        is CloseTabResult.StayOnCurrent -> onNavigateBack()
                        is CloseTabResult.NavigateBack -> onNavigateBack()
                        is CloseTabResult.SwitchTo -> { displayedNoteId = result.noteId }
                    }
                })
            }
        )

        // Loading indicator
        val isInitialLoad = loadStatus is LoadStatus.Loading && userContent.isEmpty()
        if (isInitialLoad) {
            Box(
                modifier = Modifier.weight(1f).fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(
                        text = stringResource(R.string.loading_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            }
        }

        // Editor
        if (!isInitialLoad) {
            val directiveCallbacks = rememberDirectiveCallbacks(
                editorState = editorState,
                controller = controller,
                currentNoteViewModel = currentNoteViewModel,
                recentTabsViewModel = recentTabsViewModel,
                inlineEditState = inlineEditState,
                userContent = userContent,
                onContentChanged = { userContent = it },
                onMarkUnsaved = { isSaved = false }
            )
            val buttonCallbacks = rememberButtonCallbacks(
                currentNoteViewModel = currentNoteViewModel,
                executionStates = buttonExecutionStates,
                errors = buttonErrors
            )

            ProvideInlineEditState(inlineEditState) {
                key(displayedNoteId) {
                    NoteTextField(
                        textFieldValue = textFieldValue,
                        onTextFieldValueChange = updateTextFieldValue,
                        focusRequester = mainContentFocusRequester,
                        onFocusChanged = { isFocused -> isMainContentFocused = isFocused },
                        editorState = editorState,
                        controller = controller,
                        isFingerDownFlow = isFingerDownFlow,
                        onSymbolTap = { tapInfo ->
                            when (tapInfo.symbol) {
                                TappableSymbol.ALARM -> {
                                    val lineContent = editorState.lines.getOrNull(tapInfo.lineIndex)?.text ?: ""
                                    alarmDialogLineContent = TextLineUtils.trimLineForAlarm(lineContent)
                                    alarmDialogLineIndex = tapInfo.lineIndex
                                    alarmDialogSymbolIndex = tapInfo.symbolIndexOnLine
                                    currentNoteViewModel.fetchAlarmsForLine(tapInfo.lineIndex) {
                                        showAlarmDialog = true
                                    }
                                }
                            }
                        },
                        textColor = if (isNoteDeleted) deletedNoteTextColor else Color.Black,
                        directiveResults = directiveResults,
                        directiveCallbacks = directiveCallbacks,
                        buttonCallbacks = buttonCallbacks,
                        showCompleted = showCompleted,
                        symbolOverlaysProvider = { lineIndex ->
                            currentNoteViewModel.getSymbolOverlays(lineIndex, noteAlarmsForOverlay)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        CommandBar(
            onToggleBullet = { activeController.toggleBullet() },
            onToggleCheckbox = { activeController.toggleCheckbox() },
            onIndent = { activeController.indent() },
            onUnindent = { activeController.unindent() },
            onMoveUp = {
                inlineEditState.activeSession?.let { it.isMoveInProgress = true }
                if (activeController.moveUp()) {
                    if (!inlineEditState.isActive) {
                        userContent = editorState.text
                        isSaved = false
                    }
                }
            },
            onMoveDown = {
                inlineEditState.activeSession?.let { it.isMoveInProgress = true }
                if (activeController.moveDown()) {
                    if (!inlineEditState.isActive) {
                        userContent = editorState.text
                        isSaved = false
                    }
                }
            },
            moveUpState = activeController.getMoveUpState(),
            moveDownState = activeController.getMoveDownState(),
            onPaste = { clipText, html -> activeController.paste(clipText, html) },
            isPasteEnabled = (isMainContentFocused || inlineEditState.isActive) &&
                !(inlineEditState.activeSession?.editorState?.hasSelection ?: editorState.hasSelection),
            onAddAlarm = {
                if (!inlineEditState.isActive) {
                    controller.commitUndoState(continueEditing = true)
                    val lineContent = editorState.currentLine?.text ?: ""
                    alarmDialogLineContent = TextLineUtils.trimLineForAlarm(lineContent)
                    alarmDialogLineIndex = editorState.focusedLineIndex
                    alarmDialogSymbolIndex = -1
                    showAlarmDialog = true
                }
            },
            isAlarmEnabled = isMainContentFocused && !editorState.hasSelection && !inlineEditState.isActive
        )

        AgentCommandSection(
            isExpanded = isAgentSectionExpanded,
            onExpandedChange = { isAgentSectionExpanded = it },
            agentCommand = agentCommand,
            onAgentCommandChange = { agentCommand = it },
            isProcessing = isAgentProcessing,
            onSendCommand = {
                currentNoteViewModel.processAgentCommand(userContent, agentCommand)
                agentCommand = ""
            },
            mainContentFocusRequester = mainContentFocusRequester
        )
    }
}

// --- Extracted effect composables ---

/**
 * Handles auto-save on lifecycle events (background, dispose) and undo state persistence.
 */
@Composable
private fun LifecycleAutoSaveEffect(
    controller: EditorController,
    currentNoteViewModel: CurrentNoteViewModel,
    recentTabsViewModel: RecentTabsViewModel,
    userContent: String,
    isSaved: Boolean,
    isNoteDeleted: Boolean,
    currentNoteId: String?,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentUserContent by rememberUpdatedState(userContent)
    val currentIsSaved by rememberUpdatedState(isSaved)
    val currentIsNoteDeleted by rememberUpdatedState(isNoteDeleted)
    val currentNoteIdForPersistence by rememberUpdatedState(currentNoteId)

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                controller.commitUndoState()
                currentNoteIdForPersistence?.let { noteId ->
                    UndoStatePersistence.saveStateBlocking(context, noteId, controller.undoManager)
                }
                if (!currentIsSaved && currentUserContent.isNotEmpty()) {
                    currentNoteViewModel.saveContent(currentUserContent)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.commitUndoState()
            currentNoteIdForPersistence?.let { noteId ->
                UndoStatePersistence.saveStateBlocking(context, noteId, controller.undoManager)
            }
            if (!currentIsSaved && currentUserContent.isNotEmpty()) {
                currentNoteViewModel.updateTrackedLines(currentUserContent)
                val trackedLines = currentNoteViewModel.getTrackedLines()
                recentTabsViewModel.cacheNoteContent(
                    currentNoteIdForPersistence!!,
                    trackedLines,
                    currentIsNoteDeleted
                )
                recentTabsViewModel.updateTabDisplayText(currentNoteIdForPersistence!!, currentUserContent)
                currentNoteViewModel.saveContent(currentUserContent)
            }
        }
    }
}

/**
 * Handles initial data loading: note content, undo restoration, tab loading, and retry logic.
 */
@Composable
private fun DataLoadingEffects(
    displayedNoteId: String?,
    loadStatus: LoadStatus?,
    tabsError: TabsError?,
    userContent: String,
    editorState: EditorState,
    controller: EditorController,
    currentNoteViewModel: CurrentNoteViewModel,
    recentTabsViewModel: RecentTabsViewModel,
    onContentLoaded: (String) -> Unit,
) {
    val context = LocalContext.current

    // Load content when displayed note changes
    LaunchedEffect(displayedNoteId) {
        currentNoteViewModel.loadContent(displayedNoteId, recentTabsViewModel)
    }

    // Re-execute directives when notes cache is refreshed
    LaunchedEffect(Unit) {
        currentNoteViewModel.notesCacheRefreshed.collect {
            if (userContent.isNotEmpty()) {
                currentNoteViewModel.executeDirectivesLive(userContent)
            }
        }
    }

    // Update content when loaded from ViewModel
    LaunchedEffect(loadStatus) {
        if (loadStatus is LoadStatus.Success) {
            val loadedContent = loadStatus.content
            if (loadedContent != userContent) {
                onContentLoaded(loadedContent)
                // CRITICAL: Update editorState BEFORE setBaseline() below!
                // Without this line, editorState is empty when baseline is captured,
                // which means undo can restore to empty state and LOSE USER DATA.
                editorState.updateFromText(loadedContent)
            }

            // Always set up undo state (needed even for cached content on initial load)
            val noteIdForRestore = currentNoteViewModel.getCurrentNoteId()
            val restored = UndoStatePersistence.restoreState(context, noteIdForRestore, controller.undoManager)
            if (!restored) {
                controller.resetUndoHistory()
            }
            if (!controller.undoManager.hasBaseline) {
                controller.undoManager.setBaseline(editorState)
            }
            controller.undoManager.beginEditingLine(editorState, editorState.focusedLineIndex)
            editorState.requestFocusUpdate()
        }
    }

    // Auto-retry on transient load failures
    var loadRetryCount by remember(displayedNoteId) { mutableIntStateOf(0) }
    LaunchedEffect(loadStatus, loadRetryCount) {
        if (loadStatus is LoadStatus.Error && loadRetryCount < 2) {
            delay(1500)
            loadRetryCount++
            currentNoteViewModel.loadContent(displayedNoteId, recentTabsViewModel)
        }
    }

    // Load tabs on initial composition, with retry on failure
    LaunchedEffect(Unit) {
        recentTabsViewModel.loadTabs()
    }
    val tabsError2 = tabsError
    var tabsRetryCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(tabsError2, tabsRetryCount) {
        if (tabsError2 != null && tabsRetryCount < 2) {
            delay(1500)
            tabsRetryCount++
            recentTabsViewModel.clearError()
            recentTabsViewModel.loadTabs()
        }
    }
}

/**
 * Handles content sync: tab updates, save status, content modification signals, alarm creation.
 */
@Composable
private fun ContentSyncEffects(
    currentNoteId: String?,
    loadStatus: LoadStatus?,
    saveStatus: SaveStatus?,
    contentModified: Boolean,
    alarmCreated: AlarmCreatedEvent?,
    userContent: String,
    isNoteDeleted: Boolean,
    editorState: EditorState,
    controller: EditorController,
    currentNoteViewModel: CurrentNoteViewModel,
    recentTabsViewModel: RecentTabsViewModel,
    onContentChanged: (String) -> Unit,
    onSavedChanged: (Boolean) -> Unit,
) {
    // Update tab when note is loaded
    LaunchedEffect(loadStatus, currentNoteId) {
        if (loadStatus is LoadStatus.Success && currentNoteId != null) {
            recentTabsViewModel.onNoteOpened(currentNoteId, loadStatus.content)
        }
    }

    // React to content modification signal (e.g. from Agent)
    LaunchedEffect(contentModified) {
        if (contentModified) {
            onSavedChanged(false)
            controller.resetUndoHistory()
            currentNoteId?.let { recentTabsViewModel.invalidateCache(it) }
        }
    }

    // Handle save status changes
    LaunchedEffect(saveStatus) {
        if (saveStatus is SaveStatus.Success) {
            onSavedChanged(true)
            currentNoteViewModel.markAsSaved()
            currentNoteId?.let { noteId ->
                recentTabsViewModel.updateTabDisplayText(noteId, userContent)
                val trackedLines = currentNoteViewModel.getTrackedLines()
                recentTabsViewModel.cacheNoteContent(noteId, trackedLines, isNoteDeleted)
            }
        }
    }

    // Note: Tab removal on delete is handled by the onDeleteNote callback in NoteStatusBar,
    // which closes the tab and navigates to the next one in a single step.

    // Handle alarm creation - insert symbol, record for undo, and save
    LaunchedEffect(alarmCreated) {
        alarmCreated?.let { event ->
            controller.insertAtEndOfCurrentLine(AlarmSymbolUtils.ALARM_SYMBOL)
            onContentChanged(editorState.text)
            event.alarmSnapshot?.let { snapshot ->
                controller.recordAlarmCreation(snapshot)
            }
            currentNoteViewModel.saveContent(editorState.text)
            currentNoteViewModel.clearAlarmCreatedEvent()
        }
    }
}

/**
 * Handles directive mutations that affect the current note's editor content.
 */
@Composable
private fun DirectiveMutationEffect(
    currentNoteId: String?,
    userContent: String,
    editorState: EditorState,
    controller: EditorController,
    currentNoteViewModel: CurrentNoteViewModel,
    onContentChanged: (String, TextFieldValue) -> Unit,
    onMarkUnsaved: () -> Unit,
) {
    DisposableEffect(currentNoteViewModel, controller) {
        currentNoteViewModel.onEditorContentMutated = { noteId, newContent, mutationType, alreadyPersisted, appendedText ->
            if (noteId == currentNoteId) {
                val updatedContent = when (mutationType) {
                    MutationType.CONTENT_CHANGED -> {
                        val currentLines = userContent.lines()
                        if (currentLines.size > 1) {
                            (listOf(newContent) + currentLines.drop(1)).joinToString("\n")
                        } else {
                            newContent
                        }
                    }
                    MutationType.CONTENT_APPENDED -> {
                        if (appendedText != null) userContent + "\n" + appendedText else null
                    }
                    MutationType.PATH_CHANGED -> null
                }

                updatedContent?.let {
                    editorState.updateFromText(it)
                    onContentChanged(it, TextFieldValue(it, TextRange(it.length)))
                }
                if (!alreadyPersisted) onMarkUnsaved()
                controller.resetUndoHistory()
            }
        }
        onDispose {
            currentNoteViewModel.onEditorContentMutated = null
        }
    }
}

/**
 * Status bar with undo/redo handling extracted from the main composable.
 */
@Composable
private fun NoteStatusBar(
    isSaved: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    isNoteDeleted: Boolean,
    userContent: String,
    editorState: EditorState,
    controller: EditorController,
    currentNoteViewModel: CurrentNoteViewModel,
    onContentChanged: (String) -> Unit,
    onMarkUnsaved: () -> Unit,
    showCompleted: Boolean,
    onShowCompletedToggle: () -> Unit,
    onDeleteNote: () -> Unit,
) {
    StatusBar(
        isSaved = isSaved,
        onSaveClick = {
            controller.sortCompletedToBottom()
            controller.commitUndoState(continueEditing = true)
            currentNoteViewModel.saveContent(editorState.text)
        },
        canUndo = canUndo,
        canRedo = canRedo,
        onUndoClick = {
            val expandedPositions = currentNoteViewModel.getExpandedDirectivePositions(userContent)
            controller.commitUndoState()
            val snapshot = controller.undo()
            if (snapshot != null) {
                onContentChanged(editorState.text)
                onMarkUnsaved()
                currentNoteViewModel.executeDirectivesLive(editorState.text)
                currentNoteViewModel.restoreExpandedDirectivesByPosition(editorState.text, expandedPositions)
                snapshot.createdAlarm?.let { alarm ->
                    currentNoteViewModel.deleteAlarmPermanently(alarm.id)
                }
            }
        },
        onRedoClick = {
            val expandedPositions = currentNoteViewModel.getExpandedDirectivePositions(userContent)
            controller.commitUndoState()
            val snapshot = controller.redo()
            if (snapshot != null) {
                onContentChanged(editorState.text)
                onMarkUnsaved()
                currentNoteViewModel.executeDirectivesLive(editorState.text)
                currentNoteViewModel.restoreExpandedDirectivesByPosition(editorState.text, expandedPositions)
                snapshot.createdAlarm?.let { alarm ->
                    currentNoteViewModel.recreateAlarm(
                        alarmSnapshot = alarm,
                        onAlarmCreated = { newId -> controller.updateLastUndoAlarmId(newId) },
                        onFailure = { errorMessage ->
                            val rollbackSnapshot = controller.undo()
                            val rollbackSucceeded = rollbackSnapshot != null
                            if (rollbackSucceeded) onContentChanged(editorState.text)
                            currentNoteViewModel.showRedoRollbackWarning(rollbackSucceeded, errorMessage)
                        }
                    )
                }
            }
        },
        isDeleted = isNoteDeleted,
        onDeleteClick = onDeleteNote,
        onUndeleteClick = {
            currentNoteViewModel.undeleteCurrentNote(onSuccess = {})
        },
        showCompleted = showCompleted,
        onShowCompletedToggle = onShowCompletedToggle
    )
}

@Preview(showBackground = true)
@Composable
fun CurrentNoteScreenPreview() {
    CurrentNoteScreen()
}
