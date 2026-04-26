package org.alkaline.taskbrain.ui.currentnote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import org.alkaline.taskbrain.BuildConfig
import org.alkaline.taskbrain.R
import org.alkaline.taskbrain.data.CloseTabResult
import org.alkaline.taskbrain.data.ConnectivityMonitor
import org.alkaline.taskbrain.data.NoteStore
import org.alkaline.taskbrain.data.TabState
import org.alkaline.taskbrain.dsl.runtime.MutationType
import org.alkaline.taskbrain.ui.components.OfflineBanner
import org.alkaline.taskbrain.ui.currentnote.components.AgentCommandSection
import org.alkaline.taskbrain.ui.currentnote.components.AlarmDialogMode
import org.alkaline.taskbrain.ui.currentnote.components.CommandBar
import org.alkaline.taskbrain.ui.currentnote.components.StatusBar
import org.alkaline.taskbrain.ui.currentnote.undo.UndoStatePersistence
import org.alkaline.taskbrain.ui.currentnote.undo.UnifiedUndoManager
import org.alkaline.taskbrain.ui.currentnote.util.AlarmSymbolUtils
import org.alkaline.taskbrain.ui.currentnote.util.ClipboardHtmlConverter
import org.alkaline.taskbrain.ui.currentnote.util.SymbolTapInfo
import org.alkaline.taskbrain.ui.currentnote.util.TappableSymbol
import org.alkaline.taskbrain.ui.currentnote.util.TextLineUtils

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
    recentTabsViewModel: RecentTabsViewModel = viewModel(),
) {
    // --- ViewModel state observations ---
    val saveStatus by currentNoteViewModel.saveStatus.observeAsState()
    val loadStatus by currentNoteViewModel.loadStatus.observeAsState()
    val contentModified by currentNoteViewModel.contentModified.observeAsState(false)
    val isAgentProcessing = if (BuildConfig.AGENT_COMMAND_ENABLED) {
        currentNoteViewModel.isAgentProcessing.observeAsState(false).value
    } else false
    val alarmCreated by currentNoteViewModel.alarmManager.alarmCreated.observeAsState()
    val alarmError by currentNoteViewModel.alarmManager.alarmError.observeAsState()
    val alarmPermissionWarning by currentNoteViewModel.alarmManager.alarmPermissionWarning.observeAsState(false)
    val notificationPermissionWarning by currentNoteViewModel.alarmManager.notificationPermissionWarning.observeAsState(false)
    val schedulingWarning by currentNoteViewModel.alarmManager.schedulingWarning.observeAsState()
    val isAlarmOperationPending by currentNoteViewModel.alarmManager.isAlarmOperationPending.observeAsState(false)
    val redoRollbackWarning by currentNoteViewModel.alarmManager.redoRollbackWarning.observeAsState()
    val saveWarning by currentNoteViewModel.directiveManager.saveWarning.observeAsState()
    val isNoteDeletedFromVm by currentNoteViewModel.isNoteDeleted.observeAsState(false)
    val showCompletedFromVm by currentNoteViewModel.showCompleted.observeAsState(true)
    val noteNeedsFix by currentNoteViewModel.noteNeedsFix.observeAsState(false)
    // Generation counter bumps after async cache fills, triggering recomposition
    val directiveCacheGeneration by currentNoteViewModel.directiveManager.directiveCacheGeneration.observeAsState(0)
    val buttonExecutionStates by currentNoteViewModel.directiveManager.buttonExecutionStates.observeAsState(emptyMap())
    val buttonErrors by currentNoteViewModel.directiveManager.buttonErrors.observeAsState(emptyMap())
    val recentTabs by recentTabsViewModel.tabs.observeAsState(emptyList())
    val tabsError by recentTabsViewModel.error.observeAsState()
    val currentNoteId by currentNoteViewModel.currentNoteIdLiveData.observeAsState()
    val alarmCacheForOverlay by currentNoteViewModel.alarmManager.alarmCache.observeAsState(emptyMap())
    val recurringAlarmCacheForOverlay by currentNoteViewModel.alarmManager.recurringAlarmCache.observeAsState(emptyMap())
    val alarmDialogRecurrenceConfig by currentNoteViewModel.alarmManager.recurrenceConfig.observeAsState()
    val alarmDialogRecurringAlarm by currentNoteViewModel.alarmManager.recurringAlarm.observeAsState()

    // --- Tab/note state ---
    val displayedNoteIdState = rememberDisplayedNoteId(
        initialNoteId = noteId,
        currentNoteId = currentNoteId,
    )
    var displayedNoteId by displayedNoteIdState

    // When a save completes, clear the dirty cache for the just-saved note so
    // future tab returns read from NoteStore (which has the fresh content after
    // Firestore echoes). The cache is only meant to bridge the save-echo gap.
    LaunchedEffect(saveStatus) {
        val status = saveStatus
        if (status is UnifiedSaveStatus.Saved) {
            recentTabsViewModel.invalidateCache(status.noteId)
        }
    }

    // --- Editor state + content ---
    val editorContent = rememberEditorAndContent(
        displayedNoteId = displayedNoteId,
        isNoteDeletedFromVm = isNoteDeletedFromVm,
        showCompletedFromVm = showCompletedFromVm,
        onMarkDirty = {
            currentNoteViewModel.dirty = true
            currentNoteViewModel.markAsDirty()
        },
        getCachedNoteContent = recentTabsViewModel::getCachedContent,
    )
    val editorState = editorContent.editorState
    val controller = editorContent.controller

    PushPreviousTabContentOnSwitch(
        displayedNoteId = displayedNoteId,
        currentContent = editorContent.userContent,
        onPush = currentNoteViewModel::pushContentToNoteStore,
    )

    var agentCommand by remember { mutableStateOf("") }
    var isAgentSectionExpanded by remember { mutableStateOf(false) }
    @Suppress("KotlinConstantConditions")
    val agentCommandEnabled = BuildConfig.AGENT_COMMAND_ENABLED

    // --- Directives, view notes, inline sessions ---
    val directiveState = rememberDirectiveResultsAndSessions(
        userContent = editorContent.userContent,
        displayedNoteId = displayedNoteId,
        showCompleted = editorContent.showCompleted,
        directiveCacheGeneration = directiveCacheGeneration,
        editorState = editorState,
        controller = controller,
        directiveManager = currentNoteViewModel.directiveManager,
        alarmManager = currentNoteViewModel.alarmManager,
        onInvalidateCache = recentTabsViewModel::invalidateCache,
    )
    val directiveResults = directiveState.directiveResults
    val viewNotes = directiveState.viewNotes
    val inlineEditState = directiveState.inlineEditState

    // --- Selection coordinator: mutual exclusivity for focus/selection ---
    val coordinator = remember(editorState, controller) {
        SelectionCoordinator(editorState, controller)
    }
    coordinator.inlineEditState = inlineEditState
    val activeController = coordinator.activeController

    // --- Unified undo/redo across main editor + all inline sessions ---
    val undoState = rememberUnifiedUndoState(
        controller = controller,
        editorState = editorState,
        viewNotes = viewNotes,
        inlineEditState = inlineEditState,
    )
    val unifiedUndoManager = undoState.manager
    val canUndo = undoState.canUndo
    val canRedo = undoState.canRedo

    // Track inline session edits to keep save button in sync.
    // Reading stateVersion subscribes to each session's changes.
    val anyInlineDirty = inlineEditState.viewSessions.values.any { session ->
        @Suppress("UNUSED_VARIABLE")
        val v = session.editorState.stateVersion
        session.isDirty
    }
    SideEffect {
        if (anyInlineDirty) currentNoteViewModel.markAsDirty()
    }

    // --- Alarm dialog state ---
    val alarmDialog = rememberAlarmDialogState(
        inlineEditState = inlineEditState,
        alarmManager = currentNoteViewModel.alarmManager,
    )

    // --- Effects ---
    LifecycleAutoSaveEffect(
        controller = controller,
        currentNoteViewModel = currentNoteViewModel,
        recentTabsViewModel = recentTabsViewModel,
        userContent = editorContent.userContent,
        isSaved = editorContent.isSaved,
        isNoteDeleted = editorContent.isNoteDeleted,
        currentNoteId = currentNoteId,
        inlineEditState = inlineEditState,
    )

    DataLoadingEffects(
        displayedNoteId = displayedNoteId,
        loadStatus = loadStatus,
        tabsError = tabsError,
        userContent = editorContent.userContent,
        isSaved = editorContent.isSaved,
        editorState = editorState,
        controller = controller,
        currentNoteViewModel = currentNoteViewModel,
        recentTabsViewModel = recentTabsViewModel,
        inlineEditState = inlineEditState,
        onContentLoaded = { editorContent.applyLoadedContent(it) },
    )

    ContentSyncEffects(
        currentNoteId = currentNoteId,
        loadStatus = loadStatus,
        saveStatus = saveStatus,
        contentModified = contentModified,
        alarmCreated = alarmCreated,
        userContent = editorContent.userContent,
        isNoteDeleted = editorContent.isNoteDeleted,
        editorState = editorState,
        controller = controller,
        inlineEditState = inlineEditState,
        currentNoteViewModel = currentNoteViewModel,
        recentTabsViewModel = recentTabsViewModel,
        onContentChanged = { editorContent.updateContent(it) },
        onSavedChanged = { editorContent.isSaved = it },
    )

    DirectiveMutationEffect(
        currentNoteId = currentNoteId,
        userContent = editorContent.userContent,
        editorState = editorState,
        controller = controller,
        currentNoteViewModel = currentNoteViewModel,
        onContentChanged = { content, tfv ->
            editorContent.updateContent(content)
            editorContent.textFieldValue = tfv
        },
        onMarkUnsaved = { editorContent.markUnsaved() },
    )

    // Switches the directive text in the editor if the directive type changed.
    // e.g., [alarm("id")] → [recurringAlarm("recId")] or vice versa.
    //
    // Uses the surgical replaceDirectiveText helper so only the lines actually containing
    // the directive are modified — every other line keeps its LineState (and noteIds)
    // untouched. No round-trip through updateFromText.
    fun switchDirectiveIfNeeded(newDirective: String) {
        val tapped = alarmDialog.tappedDirectiveText ?: return
        if (tapped == newDirective) return
        val before = editorState.text
        editorState.replaceDirectiveText(tapped, newDirective)
        if (editorState.text != before) {
            editorContent.updateContent(editorState.text)
            editorContent.markUnsaved()
            currentNoteViewModel.saveContent(editorState.toNoteLines())
        }
    }

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
        saveWarning = saveWarning,
        onClearSaveWarning = { currentNoteViewModel.directiveManager.clearSaveWarning() },
        onClearSaveError = { currentNoteViewModel.clearSaveError() },
        onClearLoadError = { currentNoteViewModel.clearLoadError() },
        onClearTabsError = { recentTabsViewModel.clearError() },
        onClearAlarmError = { currentNoteViewModel.alarmManager.clearAlarmError() },
        onClearAlarmPermissionWarning = { currentNoteViewModel.alarmManager.clearAlarmPermissionWarning() },
        onClearNotificationPermissionWarning = { currentNoteViewModel.alarmManager.clearNotificationPermissionWarning() },
        onClearSchedulingWarning = { currentNoteViewModel.alarmManager.clearSchedulingWarning() },
        onClearRedoRollbackWarning = { currentNoteViewModel.alarmManager.clearRedoRollbackWarning() },
        showAlarmDialog = alarmDialog.showDialog,
        alarmDialogLineContent = alarmDialog.lineContent,
        alarmDialogExistingAlarm = alarmDialog.alarm,
        alarmDialogInitialMode = alarmDialog.initialMode,
        alarmDialogRecurrenceConfig = alarmDialogRecurrenceConfig,
        alarmDialogRecurringAlarm = alarmDialogRecurringAlarm,
        alarmDialogInstanceCount = alarmDialog.siblings.size,
        onAlarmSave = { dueTime, stages ->
            val existing = alarmDialog.alarm
            if (existing != null) {
                currentNoteViewModel.alarmManager.updateAlarm(existing, dueTime, stages)
                // Non-recurring save: switch [recurringAlarm(...)] → [alarm(...)]
                switchDirectiveIfNeeded(AlarmSymbolUtils.alarmDirective(existing.id))
            } else {
                // Pre-allocate the alarm ID and embed its directive into the line so
                // the note save and the alarm doc create ride one batch.
                val alarmId = currentNoteViewModel.newAlarmId()
                val targetController = alarmDialog.inlineSession?.controller ?: controller
                targetController.insertAtEndOfCurrentLine(AlarmSymbolUtils.alarmDirective(alarmId))
                currentNoteViewModel.saveAndCreateAlarm(
                    trackedLines = editorState.toNoteLines(),
                    lineContent = alarmDialog.lineContent,
                    lineIndex = alarmDialog.lineIndex,
                    alarmId = alarmId,
                    dueTime = dueTime,
                    stages = stages,
                    inlineSession = alarmDialog.inlineSession,
                )
            }
        },
        onAlarmSaveRecurring = { dueTime, stages, recurrenceConfig ->
            val existing = alarmDialog.alarm
            if (existing != null) {
                currentNoteViewModel.alarmManager.updateRecurringAlarm(existing, dueTime, stages, recurrenceConfig)
                // Recurring save: switch [alarm(...)] → [recurringAlarm(...)]
                val recurringId = existing.recurringAlarmId
                if (recurringId != null) {
                    switchDirectiveIfNeeded(AlarmSymbolUtils.recurringAlarmDirective(recurringId))
                }
            } else {
                val recurringAlarmId = currentNoteViewModel.newRecurringAlarmId()
                val alarmId = currentNoteViewModel.newAlarmId()
                val targetController = alarmDialog.inlineSession?.controller ?: controller
                targetController.insertAtEndOfCurrentLine(AlarmSymbolUtils.recurringAlarmDirective(recurringAlarmId))
                currentNoteViewModel.saveAndCreateRecurringAlarm(
                    trackedLines = editorState.toNoteLines(),
                    lineContent = alarmDialog.lineContent,
                    lineIndex = alarmDialog.lineIndex,
                    recurringAlarmId = recurringAlarmId,
                    alarmId = alarmId,
                    dueTime = dueTime,
                    stages = stages,
                    recurrenceConfig = recurrenceConfig,
                    inlineSession = alarmDialog.inlineSession,
                )
            }
        },
        onAlarmSaveInstance = alarmDialogRecurringAlarm?.let { recurring ->
            { alarm, dueTime, stages, alsoUpdateRecurrence ->
                currentNoteViewModel.alarmManager.updateInstanceTimes(alarm, dueTime, stages, alsoUpdateRecurrence)
                // Instance save on recurring: switch [alarm(...)] → [recurringAlarm(...)]
                switchDirectiveIfNeeded(AlarmSymbolUtils.recurringAlarmDirective(recurring.id))
            }
        },
        onAlarmSaveRecurrenceTemplate = alarmDialogRecurringAlarm?.let { recurring ->
            { recId, dueTime, stages, config, alsoUpdateInstances ->
                currentNoteViewModel.alarmManager.updateRecurrenceTemplate(recId, dueTime, stages, config, alsoUpdateInstances)
                // Recurrence template save: switch [alarm(...)] → [recurringAlarm(...)]
                switchDirectiveIfNeeded(AlarmSymbolUtils.recurringAlarmDirective(recurring.id))
            }
        },
        onAlarmMarkDone = alarmDialog.alarm?.let { alarm -> { currentNoteViewModel.alarmManager.markAlarmDone(alarm.id) } },
        onAlarmMarkCancelled = alarmDialog.alarm?.let { alarm -> { currentNoteViewModel.alarmManager.cancelAlarm(alarm.id) } },
        onAlarmReactivate = alarmDialog.alarm?.let { alarm -> { currentNoteViewModel.alarmManager.reactivateAlarm(alarm.id) } },
        onAlarmDelete = alarmDialog.alarm?.let { alarm -> { currentNoteViewModel.alarmManager.deleteAlarmPermanently(alarm.id) } },
        onAlarmNavigatePrevious = if (alarmDialog.recurringId != null) {{ alarmDialog.navigatePrevious() }} else null,
        onAlarmNavigateNext = if (alarmDialog.recurringId != null) {{ alarmDialog.navigateNext() }} else null,
        alarmHasPrevious = alarmDialog.hasPrevious,
        alarmHasNext = alarmDialog.hasNext,
        onAlarmDismiss = {
            alarmDialog.dismiss()
            currentNoteViewModel.alarmManager.fetchRecurrenceConfig(null)
        },
        onFetchRecurrenceConfig = { currentNoteViewModel.alarmManager.fetchRecurrenceConfig(it) },
    )

    // Clipboard HTML formatting
    ClipboardHtmlConverter()

    // Text field value change handler
    val updateTextFieldValue: (TextFieldValue) -> Unit = { newValue ->
        val oldText = editorContent.textFieldValue.text
        editorContent.textFieldValue = newValue
        if (newValue.text != editorContent.userContent) {
            editorContent.updateContent(newValue.text)
            if (editorContent.isSaved) editorContent.isSaved = false
            val oldBracketCount = oldText.count { it == ']' }
            val newBracketCount = newValue.text.count { it == ']' }
            if (newBracketCount > oldBracketCount) {
                currentNoteViewModel.directiveManager.bumpDirectiveCacheGeneration()
            }
        }
    }

    // --- UI Composition ---
    val deletedNoteBackground = colorResource(R.color.deleted_note_background)
    val deletedNoteTextColor = colorResource(R.color.deleted_note_text)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (editorContent.isNoteDeleted) deletedNoteBackground else Color.White)
    ) {
        val notesNeedingFix by NoteStore.notesNeedingFix.collectAsState()
        RecentTabsBar(
            tabs = recentTabs,
            currentNoteId = displayedNoteId ?: "",
            notesNeedingFix = notesNeedingFix,
            onTabClick = { targetNoteId ->
                if (!editorContent.isSaved && editorContent.userContent.isNotEmpty()) {
                    val dirtySessions = inlineEditState.getAllDirtySessions()
                    val noteLines = editorState.toNoteLines()
                    val currentTabId = displayedNoteId
                    // Cache dirty tracked lines before firing the async save:
                    // if the user returns to this tab before the Firestore echo
                    // arrives, loadContent's Path 1 restores the edit instead of
                    // reading stale rawNotes. Cleared on save success below.
                    if (currentTabId != null) {
                        recentTabsViewModel.cacheNoteContent(
                            currentTabId,
                            noteLines,
                            isDeleted = editorContent.isNoteDeleted,
                            isDirty = true,
                        )
                    }
                    currentNoteViewModel.saveAll(noteLines, dirtySessions)
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

        // Offline state (must be declared before NoteStatusBar which uses showOfflineIcon)
        val isOnline by ConnectivityMonitor.isOnline.collectAsState()
        var showOfflineIcon by remember { mutableStateOf(false) }

        NoteStatusBar(
            saveStatus = saveStatus,
            noteNeedsFix = noteNeedsFix,
            canUndo = canUndo && !isAlarmOperationPending,
            canRedo = canRedo && !isAlarmOperationPending,
            isNoteDeleted = editorContent.isNoteDeleted,
            userContent = editorContent.userContent,
            editorState = editorState,
            controller = controller,
            coordinator = coordinator,
            unifiedUndoManager = unifiedUndoManager,
            currentNoteViewModel = currentNoteViewModel,
            inlineEditState = inlineEditState,
            onContentChanged = { editorContent.updateContent(it) },
            onSyncUserContent = { editorContent.userContent = it },
            onMarkUnsaved = { editorContent.markUnsaved() },
            showCompleted = editorContent.showCompleted,
            onShowCompletedToggle = { currentNoteViewModel.toggleShowCompleted() },
            showOfflineIcon = showOfflineIcon,
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

        // Offline banner
        OfflineBanner(
            isOnline = isOnline,
            onCollapsedStateChange = { showOfflineIcon = it }
        )

        // Loading indicator
        val isInitialLoad = loadStatus is LoadStatus.Loading && editorContent.userContent.isEmpty()
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
            NoteEditorBody(
                displayedNoteId = displayedNoteId,
                editorContent = editorContent,
                coordinator = coordinator,
                inlineEditState = inlineEditState,
                directiveResults = directiveResults,
                alarmCacheForOverlay = alarmCacheForOverlay,
                recurringAlarmCacheForOverlay = recurringAlarmCacheForOverlay,
                buttonExecutionStates = buttonExecutionStates,
                buttonErrors = buttonErrors,
                isFingerDownFlow = isFingerDownFlow,
                deletedNoteTextColor = deletedNoteTextColor,
                currentNoteViewModel = currentNoteViewModel,
                recentTabsViewModel = recentTabsViewModel,
                onAlarmSymbolTap = { tapInfo ->
                    when (tapInfo.symbol) {
                        TappableSymbol.ALARM -> openAlarmDialogForTappedSymbol(
                            tapInfo,
                            editorState,
                            alarmDialog,
                            currentNoteViewModel.alarmManager,
                        )
                    }
                },
                onTextFieldValueChange = updateTextFieldValue,
                modifier = Modifier.weight(1f),
            )
        }

        fun guarded(action: () -> Unit) {
            coordinator.withFocusGuard { action() }
        }

        CommandBar(
            onToggleBullet = { guarded { activeController.toggleBullet() } },
            onToggleCheckbox = { guarded { activeController.toggleCheckbox() } },
            onIndent = { guarded { activeController.indent() } },
            onUnindent = { guarded { activeController.unindent() } },
            onMoveUp = {
                guarded {
                    if (activeController.moveUp()) {
                        if (!inlineEditState.isActive) {
                            editorContent.updateContent(editorState.text)
                            editorContent.markUnsaved()
                        }
                    }
                }
            },
            onMoveDown = {
                guarded {
                    if (activeController.moveDown()) {
                        if (!inlineEditState.isActive) {
                            editorContent.updateContent(editorState.text)
                            editorContent.markUnsaved()
                        }
                    }
                }
            },
            moveUpState = activeController.getMoveUpState(),
            moveDownState = activeController.getMoveDownState(),
            onPaste = { clipText, html -> guarded { activeController.paste(clipText, html) } },
            isPasteEnabled = (editorContent.isMainContentFocused || inlineEditState.isActive) &&
                !(inlineEditState.activeSession?.editorState?.hasSelection ?: editorState.hasSelection),
            onAddAlarm = {
                val activeState = coordinator.activeState
                activeController.commitUndoState(continueEditing = true)
                alarmDialog.openForNewAlarm(
                    lineContent = TextLineUtils.trimLineForAlarm(activeState.currentLine?.text ?: ""),
                    lineIndex = activeState.focusedLineIndex,
                    inlineSession = coordinator.activeSession,
                )
            },
            isAlarmEnabled = (editorContent.isMainContentFocused || inlineEditState.isActive) &&
                !coordinator.activeState.hasSelection,
        )

        if (agentCommandEnabled) {
            AgentCommandSection(
                isExpanded = isAgentSectionExpanded,
                onExpandedChange = { isAgentSectionExpanded = it },
                agentCommand = agentCommand,
                onAgentCommandChange = { agentCommand = it },
                isProcessing = isAgentProcessing,
                onSendCommand = {
                    currentNoteViewModel.processAgentCommand(editorContent.userContent, agentCommand)
                    agentCommand = ""
                },
                mainContentFocusRequester = editorContent.mainContentFocusRequester,
                enabled = isOnline,
            )
        }
    }
}

/**
 * Splits cleanly between recurring and single-instance directives based on the
 * IDs in [tapInfo]. The async fetch arms `alarmDialog.alarm` and flips
 * `showDialog` only after the alarm record returns, so a missing alarm doesn't
 * leave the dialog open with no contents.
 */
private fun openAlarmDialogForTappedSymbol(
    tapInfo: SymbolTapInfo,
    editorState: EditorState,
    alarmDialog: AlarmDialogState,
    alarmManager: NoteAlarmManager,
) {
    val lineContent = TextLineUtils.trimLineForAlarm(
        editorState.lines.getOrNull(tapInfo.lineIndex)?.text ?: ""
    )
    when {
        tapInfo.recurringAlarmId != null -> {
            alarmDialog.beginOpenForTappedRecurring(
                lineContent = lineContent,
                lineIndex = tapInfo.lineIndex,
                recurringId = tapInfo.recurringAlarmId,
                directiveText = AlarmSymbolUtils.recurringAlarmDirective(tapInfo.recurringAlarmId),
            )
            alarmManager.fetchRecurringAlarmInstance(tapInfo.recurringAlarmId) { alarm ->
                alarmDialog.alarm = alarm
                alarmDialog.showDialog = true
            }
        }
        tapInfo.alarmId != null -> {
            alarmDialog.beginOpenForTappedAlarm(
                lineContent = lineContent,
                lineIndex = tapInfo.lineIndex,
                alarmId = tapInfo.alarmId,
                directiveText = AlarmSymbolUtils.alarmDirective(tapInfo.alarmId),
            )
            alarmManager.fetchAlarmById(tapInfo.alarmId) { alarm ->
                alarmDialog.alarm = alarm
                alarmDialog.showDialog = true
            }
        }
    }
}

// --- Extracted effect composables ---

/**
 * Handles auto-save on lifecycle events (background, dispose) and undo state persistence.
 * Saves both the main note and all dirty inline edit sessions.
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
    inlineEditState: InlineEditState?,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentUserContent by rememberUpdatedState(userContent)
    val currentIsSaved by rememberUpdatedState(isSaved)
    val currentIsNoteDeleted by rememberUpdatedState(isNoteDeleted)
    val currentNoteIdForPersistence by rememberUpdatedState(currentNoteId)
    val currentInlineEditState by rememberUpdatedState(inlineEditState)

    // Track whether the dirty-note save has already fired (ON_STOP or onDispose —
    // whichever runs first). Without this guard, both fire saveAll with the same
    // content, launching two concurrent persistCurrentNote coroutines that race and
    // can produce duplicate or conflicting Firestore writes.
    val alreadySaved = remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                controller.commitUndoState()
                currentNoteIdForPersistence?.let { noteId ->
                    UndoStatePersistence.saveStateBlocking(context, noteId, controller.undoManager)
                }
                if (!alreadySaved.value && !currentIsSaved && currentUserContent.isNotEmpty()) {
                    alreadySaved.value = true
                    val dirtySessions = currentInlineEditState?.getAllDirtySessions() ?: emptyList()
                    currentNoteViewModel.saveAll(
                        controller.state.toNoteLines(),
                        dirtySessions
                    )
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
                val noteLines = controller.state.toNoteLines()
                recentTabsViewModel.cacheNoteContent(
                    currentNoteIdForPersistence!!,
                    noteLines,
                    currentIsNoteDeleted,
                    isDirty = true
                )
                recentTabsViewModel.updateTabDisplayText(currentNoteIdForPersistence!!, currentUserContent)
                if (!alreadySaved.value) {
                    val dirtySessions = currentInlineEditState?.getAllDirtySessions() ?: emptyList()
                    currentNoteViewModel.saveAll(
                        noteLines,
                        dirtySessions
                    )
                }
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
    isSaved: Boolean,
    editorState: EditorState,
    controller: EditorController,
    currentNoteViewModel: CurrentNoteViewModel,
    recentTabsViewModel: RecentTabsViewModel,
    inlineEditState: InlineEditState?,
    onContentLoaded: (String) -> Unit,
) {
    val context = LocalContext.current

    // Load content when displayed note changes.
    // Flush any dirty inline edit to NoteStore before loading the new note.
    // Focus loss fires AFTER recomposition, so the inline edit's onSave hasn't
    // run yet — push the dirty content to NoteStore here so loadContent sees it.
    LaunchedEffect(displayedNoteId) {
        inlineEditState?.activeSession?.let { session ->
            if (session.isDirty) {
                // Only save if the session's content is actually NEWER than NoteStore.
                // The user may have edited the note directly (on another tab) after the
                // inline session was created — NoteStore would have the newer content.
                // Saving the stale session content would overwrite the direct edit.
                val storeContent = NoteStore.getNoteById(session.noteId)?.content
                val sessionContent = session.currentContent
                if (storeContent == null || storeContent == session.originalContent || sessionContent == storeContent) {
                    // NoteStore hasn't changed since the session started, or matches session — safe to save
                    if (storeContent != sessionContent) {
                        currentNoteViewModel.directiveManager.saveInlineEditSession(session)
                    }
                }
                // else: NoteStore has different content from a direct edit — don't overwrite
            }
        }
        // Skip the load if the ViewModel just resolved this noteId from a null→noteId
        // transition (the LaunchedEffect(currentNoteId) sync). The first loadContent(null)
        // already loaded this note; loading again would be redundant and races with the
        // first load's background refresh coroutine.
        val alreadyLoaded = displayedNoteId != null &&
            displayedNoteId == currentNoteViewModel.getCurrentNoteId() &&
            currentNoteViewModel.loadStatus.value.let { it is LoadStatus.Success && it.noteId == displayedNoteId }
        if (!alreadyLoaded) {
            currentNoteViewModel.loadContent(displayedNoteId, recentTabsViewModel)
        }
    }

    // Re-execute directives when notes cache is refreshed
    LaunchedEffect(Unit) {
        currentNoteViewModel.directiveManager.notesCacheRefreshed.collect {
            if (userContent.isNotEmpty()) {
                currentNoteViewModel.directiveManager.bumpDirectiveCacheGeneration()
            }
        }
    }

    // Update content when loaded from ViewModel
    LaunchedEffect(loadStatus) {
        if (loadStatus is LoadStatus.Success) {
            // Guard: only apply content if it's for the currently displayed note.
            // Stale results from a previous loadContent can arrive after a tab switch.
            if (loadStatus.noteId != displayedNoteId) return@LaunchedEffect
            val loadedContent = loadStatus.content
            if (loadedContent != userContent) {
                onContentLoaded(loadedContent)
                // CRITICAL: Update editorState BEFORE setBaseline() below!
                // Without this line, editorState is empty when baseline is captured,
                // which means undo can restore to empty state and LOSE USER DATA.
                //
                // Preserve cursor on external reloads (editor already has content)
                editorState.initFromNoteLines(
                    loadStatus.lines,
                    preserveCursor = editorState.lines.isNotEmpty(),
                )
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

    // Update EditorState noteIds when save assigns new IDs to lines
    LaunchedEffect(Unit) {
        currentNoteViewModel.newlyAssignedNoteIds.collect { newIds ->
            for ((index, newId) in newIds) {
                editorState.lines.getOrNull(index)?.let { line ->
                    if (line.noteIds.isEmpty()) {
                        line.noteIds = listOf(newId)
                    }
                }
            }
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
    saveStatus: UnifiedSaveStatus?,
    contentModified: Boolean,
    alarmCreated: AlarmCreatedEvent?,
    userContent: String,
    isNoteDeleted: Boolean,
    editorState: EditorState,
    controller: EditorController,
    inlineEditState: InlineEditState,
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
        if (saveStatus is UnifiedSaveStatus.Saved) {
            onSavedChanged(true)
            currentNoteViewModel.markAsSaved()
            // Use saveStatus.noteId (not currentNoteId) because a tab switch
            // may have changed currentNoteId before the save completed.
            if (saveStatus.noteId == currentNoteId && userContent.isNotEmpty()) {
                recentTabsViewModel.updateTabDisplayText(saveStatus.noteId, userContent)
            }
        }
    }

    // Note: Tab removal on delete is handled by the onDeleteNote callback in NoteStatusBar,
    // which closes the tab and navigates to the next one in a single step.

    LaunchedEffect(alarmCreated) {
        alarmCreated?.let { event ->
            event.alarmSnapshot?.let { snapshot ->
                val session = inlineEditState.activeSession
                val isInlineAlarm = session != null && snapshot.noteId == session.noteId
                val targetController = if (isInlineAlarm) session!!.controller else controller
                targetController.recordAlarmCreation(snapshot)
            }
            currentNoteViewModel.directiveManager.bumpDirectiveCacheGeneration()
            currentNoteViewModel.alarmManager.clearAlarmCreatedEvent()
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
        currentNoteViewModel.directiveManager.onEditorContentMutated = { noteId, newContent, mutationType, alreadyPersisted, appendedText ->
            if (noteId == currentNoteId) {
                // Apply the mutation surgically — only the affected lines are touched, so
                // every other line keeps its noteIds. No round-trip through updateFromText.
                val mutated = when (mutationType) {
                    MutationType.CONTENT_CHANGED -> {
                        editorState.replaceFirstLineContent(newContent)
                        true
                    }
                    MutationType.CONTENT_APPENDED -> {
                        if (appendedText != null) {
                            editorState.appendContent(appendedText)
                            true
                        } else false
                    }
                    MutationType.PATH_CHANGED -> false
                }
                if (mutated) {
                    val updatedText = editorState.text
                    onContentChanged(updatedText, TextFieldValue(updatedText, TextRange(updatedText.length)))
                }
                if (!alreadyPersisted) onMarkUnsaved()
                controller.resetUndoHistory()
            }
        }
        onDispose {
            currentNoteViewModel.directiveManager.onEditorContentMutated = null
        }
    }
}

/**
 * Status bar with undo/redo handling extracted from the main composable.
 */
@Composable
private fun NoteStatusBar(
    saveStatus: UnifiedSaveStatus?,
    noteNeedsFix: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    isNoteDeleted: Boolean,
    userContent: String,
    editorState: EditorState,
    controller: EditorController,
    coordinator: SelectionCoordinator,
    unifiedUndoManager: UnifiedUndoManager,
    currentNoteViewModel: CurrentNoteViewModel,
    inlineEditState: InlineEditState?,
    onContentChanged: (String) -> Unit,
    onSyncUserContent: (String) -> Unit,
    onMarkUnsaved: () -> Unit,
    showCompleted: Boolean,
    onShowCompletedToggle: () -> Unit,
    onDeleteNote: () -> Unit,
    showOfflineIcon: Boolean,
) {
    val activeContextId = coordinator.activeSession?.let { inlineContextId(it.noteId) } ?: MAIN_CONTEXT_ID
    val activateEditorByContextId: (String) -> Unit = { contextId ->
        if (contextId == MAIN_CONTEXT_ID) {
            coordinator.activate(EditorId.Parent)
        } else {
            coordinator.activate(EditorId.View(noteIdFromInlineContextId(contextId)))
        }
    }

    StatusBar(
        saveStatus = saveStatus ?: UnifiedSaveStatus.Idle,
        noteNeedsFix = noteNeedsFix,
        onSaveClick = {
            controller.sortCompletedToBottom()
            controller.commitUndoState(continueEditing = true)
            val dirtySessions = inlineEditState?.getAllDirtySessions() ?: emptyList()
            currentNoteViewModel.saveAll(
                editorState.toNoteLines(),
                dirtySessions
            )
        },
        canUndo = canUndo,
        canRedo = canRedo,
        onUndoClick = {
            val expandedHashes = currentNoteViewModel.directiveManager.getExpandedDirectiveHashes()
            val result = unifiedUndoManager.undo(activeContextId, activateEditorByContextId)
            if (result != null) {
                onSyncUserContent(editorState.text)
                onMarkUnsaved()
                currentNoteViewModel.directiveManager.restoreExpandedDirectiveHashes(expandedHashes)
                result.snapshot.createdAlarm?.let { alarm ->
                    currentNoteViewModel.alarmManager.deleteAlarmPermanently(alarm.id)
                }
            }
        },
        onRedoClick = {
            val expandedHashes = currentNoteViewModel.directiveManager.getExpandedDirectiveHashes()
            val result = unifiedUndoManager.redo(activeContextId, activateEditorByContextId)
            if (result != null) {
                onSyncUserContent(editorState.text)
                onMarkUnsaved()
                currentNoteViewModel.directiveManager.restoreExpandedDirectiveHashes(expandedHashes)
                result.snapshot.createdAlarm?.let { alarm ->
                    currentNoteViewModel.alarmManager.recreateAlarm(
                        alarmSnapshot = alarm,
                        onAlarmCreated = { newId ->
                            controller.updateLastUndoAlarmId(newId)
                            // Update alarm directive in text with new ID. Surgical:
                            // only the line(s) containing the old directive are modified;
                            // every other line keeps its noteIds untouched.
                            val oldDirective = AlarmSymbolUtils.alarmDirective(alarm.id)
                            val newDirective = AlarmSymbolUtils.alarmDirective(newId)
                            val before = editorState.text
                            editorState.replaceDirectiveText(oldDirective, newDirective)
                            if (editorState.text != before) {
                                onContentChanged(editorState.text)
                                currentNoteViewModel.saveContent(editorState.toNoteLines())
                            }
                        },
                        onFailure = { errorMessage ->
                            val rollbackSnapshot = controller.undo()
                            val rollbackSucceeded = rollbackSnapshot != null
                            if (rollbackSucceeded) onContentChanged(editorState.text)
                            currentNoteViewModel.alarmManager.showRedoRollbackWarning(rollbackSucceeded, errorMessage)
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
        onShowCompletedToggle = onShowCompletedToggle,
        showOfflineIcon = showOfflineIcon
    )
}

@Preview(showBackground = true)
@Composable
fun CurrentNoteScreenPreview() {
    CurrentNoteScreen()
}
